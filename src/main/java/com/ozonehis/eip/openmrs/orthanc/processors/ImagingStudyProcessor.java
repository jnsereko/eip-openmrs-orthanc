/*
 * Copyright © 2024, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.openmrs.orthanc.processors;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.ozonehis.eip.openmrs.orthanc.Constants;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ozonehis.eip.openmrs.orthanc.handlers.openmrs.OpenmrsAttachmentHandler;
import com.ozonehis.eip.openmrs.orthanc.handlers.openmrs.OpenmrsDiagnosticReportHandler;
import com.ozonehis.eip.openmrs.orthanc.handlers.openmrs.OpenmrsObsHandler;
import com.ozonehis.eip.openmrs.orthanc.handlers.openmrs.OpenmrsPatientHandler;
import com.ozonehis.eip.openmrs.orthanc.handlers.orthanc.OrthancImagingStudyHandler;
import com.ozonehis.eip.openmrs.orthanc.models.imagingStudy.Study;
import com.ozonehis.eip.openmrs.orthanc.models.obs.Attachment;
import com.ozonehis.eip.openmrs.orthanc.models.series.Series;
import com.ozonehis.eip.openmrs.orthanc.repository.ProcessedStudyRepository;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.ProducerTemplate;
import org.hl7.fhir.r4.model.Patient;
import org.openmrs.eip.EIPException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
@Slf4j
@Setter
@Getter
@Component
public class ImagingStudyProcessor implements Processor {
    private static final String ORTHANC_RENDERED_IMAGE_ENDPOINT = "%s/instances/%s/rendered";
    private static final String STRUCTURED_REPORT_MODALITY = "SR";
    /**
     * Text of the result Observation that marks a study as received. It used to end with the Stone
     * viewer URL, but that link answers 403 without a token, and studies are opened from the
     * Imagerie card instead (UVL-EMR#494). The Observation itself stays: the SR report and the
     * orphaned-study cleanup find it through {@link ProcessedStudyRepository}. The attachment
     * caption keeps the viewer URL, because the card reads the orthancId from it.
     */
    static final String STUDY_RECEIVED_TEXT =
            "Étude d'imagerie reçue – ouvrir depuis la carte Imagerie (Pièces jointes).";
    /**
     * How long a processed study's attachment check is trusted before OpenMRS is asked again. The
     * poll runs every 10 seconds over up to 100 studies, so checking every time would add an obs
     * search per study per poll; an hour still brings an attachment back soon after a restore.
     */
    static final Duration ATTACHMENT_RECHECK_INTERVAL = Duration.ofHours(1);
    @Autowired
    private ProcessedStudyRepository processedStudyRepository;
    @Value("${orthanc.publicUrl:${orthanc.baseUrl}}")
    private String orthancPublicUrl;

    @Value("${orthanc.baseUrl}")
    private String orthancBaseUrl;
    @Value("${eip.attachment.concept}")
    private String attachmentConceptId;
    @Autowired
    private OpenmrsPatientHandler openmrsPatientHandler;
    @Autowired
    private OpenmrsDiagnosticReportHandler openmrsDiagnosticReportHandler;
    @Autowired
    private OrthancImagingStudyHandler orthancImagingStudyHandler;
    @Autowired
    private OpenmrsAttachmentHandler openmrsAttachmentHandler;
    @Autowired
    private OpenmrsObsHandler openmrsObsHandler;

    private Clock clock = Clock.systemUTC();

    /** Processed studies whose attachment was last checked, and when. */
    private final Map<String, Instant> attachmentCheckedAt = new ConcurrentHashMap<>();
    @Override
    public void process(Exchange exchange) {
        try (ProducerTemplate producerTemplate = exchange.getContext().createProducerTemplate()) {
            String body = exchange.getMessage().getBody(String.class);
            ObjectMapper mapper = new ObjectMapper();
            Study[] studies = mapper.readValue(body, Study[].class);
            for (Study study : studies) {
                // Use OtherPatientIDs first, fall back to PatientID
                String patientIdentifier = study.getPatientMainDicomTags().getOtherPatientIDs();
                if (patientIdentifier == null || patientIdentifier.isEmpty()) {
                    patientIdentifier = study.getPatientMainDicomTags().getPatientID();
                }
                if (patientIdentifier == null || patientIdentifier.isEmpty()) {
                    log.warn("No patient identifier found in DICOM study {}, skipping", study.id);
                    continue;
                }
                Patient openmrsPatient = openmrsPatientHandler.getPatientByIdentifier(patientIdentifier);
                if (openmrsPatient == null || openmrsPatient.getIdentifier().isEmpty()) {
                    continue;
                }
                String patientUUID = openmrsPatient.getIdPart();
                String studyInstanceUID = study.getImagingStudyMainDicomTags().getStudyInstanceUID();
                if (processedStudyRepository.exists(study.id)) {
                    log.debug("DiagnosticReport already processed for study {}", study.id);
                    ensureAttachment(producerTemplate, study, patientUUID);
                    continue;
                }
                String studyDate = study.getImagingStudyMainDicomTags().getStudyDate();
                String resultUUID = openmrsDiagnosticReportHandler.saveResult(
                        producerTemplate,
                        patientUUID,
                        Constants.GENERAL_PATIENT_NOTE_CONCEPT_UUID,
                        STUDY_RECEIVED_TEXT,
                        studyDate);
                processedStudyRepository.save(study.id, patientUUID, resultUUID);
                // Upload preview image as attachment
                try {
                    if (!doesObsExists(producerTemplate, patientUUID, studyInstanceUID)) {
                        createAttachment(producerTemplate, study, patientUUID);
                    }
                } catch (Exception e) {
                    log.warn("Could not save attachment for study {}: {}", study.id, e.getMessage());
                }
            }
        } catch (Exception e) {
            throw new EIPException(String.format("Error processing ImagingStudy: %s", e.getMessage()));
        }
    }
    /**
     * Puts back the preview attachment of a study that is already processed, if OpenMRS no longer
     * has it.
     *
     * <p>{@code eip_processed_orthanc_study} lives in the bridge's own database, so it survives an
     * OpenMRS data loss or restore that the attachment does not. The study then stayed marked as
     * done and never came back on the Imagerie card (UVL-EMR#578). Only the attachment is
     * re-created: the study-received Observation and the tracking row are left as they are, and
     * nothing else is re-run. The check is repeated at most every
     * {@link #ATTACHMENT_RECHECK_INTERVAL}; a failed check is not remembered, so it is retried on
     * the next poll.
     */
    private void ensureAttachment(ProducerTemplate producerTemplate, Study study, String patientUUID) {
        Instant now = clock.instant();
        Instant checkedAt = attachmentCheckedAt.get(study.id);
        if (checkedAt != null && checkedAt.plus(ATTACHMENT_RECHECK_INTERVAL).isAfter(now)) {
            return;
        }
        String studyInstanceUID = study.getImagingStudyMainDicomTags().getStudyInstanceUID();
        try {
            if (!doesObsExists(producerTemplate, patientUUID, studyInstanceUID)) {
                log.info(
                        "Study {} is already processed but patient {} has no attachment for it, re-creating it",
                        study.id,
                        patientUUID);
                createAttachment(producerTemplate, study, patientUUID);
            }
            attachmentCheckedAt.put(study.id, now);
        } catch (Exception e) {
            log.warn("Could not check the attachment of processed study {}: {}", study.id, e.getMessage());
        }
    }
    /**
     * Attaches the study's first renderable image to the patient's chart.
     *
     * <p>A study is not a single image: it holds several series, and this bridge writes its own
     * Structured Report back into the study as soon as a report exists. Structured Reports carry no
     * pixel data, so {@code /instances/{id}/rendered} answers 415 for them and
     * {@link OrthancImagingStudyHandler#fetchStudyBinaryData} returns null. Taking
     * {@code series[0].instances[0]} blindly therefore attached nothing at all whenever an SR
     * sorted first - and once the bridge has written one, that is the ordinary case, not an edge
     * case. Measured on UAT: one XR series and four SR series in the same study, SR first, so the
     * chart showed a broken thumbnail with no image behind it.
     *
     * <p>So walk the series and take the first instance Orthanc will actually render. SR series are
     * skipped up front to avoid a request that is known to fail; anything else is tried, because
     * "renders or not" is Orthanc's judgement to make, not a list of modalities kept in here.
     */
    private void createAttachment(ProducerTemplate producerTemplate, Study study, String patientUUID)
            throws IOException {
        if (study.getSeries() == null || study.getSeries().isEmpty()) {
            log.warn("Study {} has no series, nothing to attach", study.id);
            return;
        }
        for (String seriesID : study.getSeries()) {
            Series series;
            try {
                series = orthancImagingStudyHandler.getSeriesByID(producerTemplate, seriesID);
            } catch (Exception e) {
                log.warn("Could not fetch series {} of study {}: {}", seriesID, study.id, e.getMessage());
                continue;
            }
            if (series == null || series.getInstances() == null || series.getInstances().isEmpty()) {
                continue;
            }
            if (series.getMainDicomTags() != null
                    && STRUCTURED_REPORT_MODALITY.equalsIgnoreCase(series.getMainDicomTags().getModality())) {
                log.debug("Skipping Structured Report series {} of study {}", seriesID, study.id);
                continue;
            }
            for (String instanceID : series.getInstances()) {
                String studyImageUrl = String.format(ORTHANC_RENDERED_IMAGE_ENDPOINT, orthancBaseUrl, instanceID);
                byte[] orthancStudyBinaryData = orthancImagingStudyHandler.fetchStudyBinaryData(studyImageUrl);
                if (orthancStudyBinaryData != null && orthancStudyBinaryData.length > 0) {
                    openmrsAttachmentHandler.saveAttachment(
                            orthancStudyBinaryData,
                            patientUUID,
                            study.getImagingStudyMainDicomTags().getStudyInstanceUID(),
                            study.id);
                    log.info(
                            "Saved attachment for patient {} study {} from instance {} ({} bytes)",
                            patientUUID,
                            study.id,
                            instanceID,
                            orthancStudyBinaryData.length);
                    return;
                }
            }
        }
        // Deliberately a warning, not an exception: a study can legitimately be reports only, and
        // the study-received Observation above is already saved either way.
        log.warn("No renderable instance found in study {}, no image attached", study.id);
    }
    /**
     * The search asks OpenMRS for this patient's attachments, but OpenMRS ignores search parameters
     * it does not support instead of refusing them, so the patient is checked here as well whenever
     * the obs carries one.
     */
    private boolean doesObsExists(ProducerTemplate producerTemplate, String patientUUID, String imagingStudyID)
            throws JsonProcessingException {
        List<Attachment> attachmentList =
                openmrsObsHandler.getObsByPatientUUIDAndConceptUUID(producerTemplate, patientUUID, attachmentConceptId);
        if (attachmentList == null) {
            return false;
        }
        for (Attachment attachment : attachmentList) {
            if (attachment.getPerson() != null
                    && attachment.getPerson().getUuid() != null
                    && !attachment.getPerson().getUuid().equals(patientUUID)) {
                continue;
            }
            if (attachment.getComment() != null && attachment.getComment().contains(imagingStudyID)) {
                return true;
            }
        }
        return false;
    }
}
