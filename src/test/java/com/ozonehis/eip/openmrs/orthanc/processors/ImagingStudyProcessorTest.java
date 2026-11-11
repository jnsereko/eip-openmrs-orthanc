/*
 * Copyright © 2024, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.openmrs.orthanc.processors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;
import static org.openmrs.eip.fhir.Constants.HEADER_FHIR_EVENT_TYPE;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ozonehis.eip.openmrs.orthanc.Utils;
import com.ozonehis.eip.openmrs.orthanc.handlers.openmrs.OpenmrsAttachmentHandler;
import com.ozonehis.eip.openmrs.orthanc.handlers.openmrs.OpenmrsDiagnosticReportHandler;
import com.ozonehis.eip.openmrs.orthanc.handlers.openmrs.OpenmrsObsHandler;
import com.ozonehis.eip.openmrs.orthanc.handlers.openmrs.OpenmrsPatientHandler;
import com.ozonehis.eip.openmrs.orthanc.handlers.orthanc.OrthancImagingStudyHandler;
import com.ozonehis.eip.openmrs.orthanc.models.obs.Attachment;
import com.ozonehis.eip.openmrs.orthanc.models.series.Series;
import com.ozonehis.eip.openmrs.orthanc.repository.ProcessedStudyRepository;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import org.apache.camel.Exchange;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
class ImagingStudyProcessorTest extends BaseProcessorTest {

    private static final String PATIENT_ID = "866f25bf-d930-4886-9332-75443047e38e";

    private static final String PATIENT_IDENTIFIER = "10000Y";

    private static final String STUDY_ID = "2.16.840.1.113669.632.20.1211.10000315526";

    private static final String ORTHANC_STUDY_ID = "0dff9280-2375f586-1297870c-4cbd6d1c-527e36e5";

    private static final String RESULT_UUID = "5b1f1e5e-7c4a-4c62-9a0b-4d1c2f3a6b7e";

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private OpenmrsPatientHandler openmrsPatientHandler;

    @Mock
    private OpenmrsAttachmentHandler openmrsAttachmentHandler;

    @Mock
    private OrthancImagingStudyHandler orthancImagingStudyHandler;

    @Mock
    private OpenmrsObsHandler openmrsObsHandler;

    @Mock
    private OpenmrsDiagnosticReportHandler openmrsDiagnosticReportHandler;

    @Mock
    private ProcessedStudyRepository processedStudyRepository;

    @InjectMocks
    private ImagingStudyProcessor imagingStudyProcessor;

    private static AutoCloseable mocksCloser;

    @BeforeEach
    void setup() {
        mocksCloser = openMocks(this);
    }

    @AfterAll
    static void close() throws Exception {
        mocksCloser.close();
    }

    @Test
    void shouldProcessImagingStudyWhenPatientIdentifierMatches() throws IOException {
        // Setup
        Patient patient = new Patient();
        patient.setId(PATIENT_ID);
        patient.setIdentifier(Collections.singletonList(new Identifier().setValue(PATIENT_IDENTIFIER)));

        String studiesString = new Utils().readJSON("response/orthanc-studies-response.json");
        Exchange exchange = createExchange(studiesString, "c");

        when(openmrsPatientHandler.getPatientByIdentifier(PATIENT_IDENTIFIER)).thenReturn(patient);
        when(orthancImagingStudyHandler.getSeriesByID(any(), any())).thenReturn(getSeries());
        // An empty rendering counts as "not renderable", so the image needs at least one byte.
        when(orthancImagingStudyHandler.fetchStudyBinaryData(any())).thenReturn(new byte[] {1});

        // Act
        imagingStudyProcessor.process(exchange);

        // Assert
        assertEquals(exchange.getMessage().getHeader(HEADER_FHIR_EVENT_TYPE), "c");
        verify(openmrsAttachmentHandler, times(1)).saveAttachment(any(), any(), any(), any());
    }

    @Test
    void shouldNotThrowErrorWhenStudyOtherPatientIdDoesNotMatchPatientIdentifier() throws IOException {
        // Setup
        Patient patient = new Patient();
        patient.setId(PATIENT_ID);
        patient.setIdentifier(Collections.singletonList(new Identifier().setValue("XYZ123")));

        String studiesString = new Utils().readJSON("response/orthanc-studies-response.json");
        Exchange exchange = createExchange(studiesString, "c");

        when(openmrsPatientHandler.getPatientByIdentifier(PATIENT_IDENTIFIER)).thenReturn(null);

        // Act
        imagingStudyProcessor.process(exchange);

        // Assert
        assertEquals(exchange.getMessage().getHeader(HEADER_FHIR_EVENT_TYPE), "c");
        verify(orthancImagingStudyHandler, times(0)).getSeriesByID(any(), any());
        verify(orthancImagingStudyHandler, times(0)).fetchStudyBinaryData(any());
        verify(openmrsAttachmentHandler, times(0)).saveAttachment(any(), any(), any(), any());
    }

    @Test
    void shouldNotCreateAttachmentWhenObsWithSameStudyAlreadyExists() throws IOException {
        // Setup
        Patient patient = new Patient();
        patient.setId(PATIENT_ID);
        patient.setIdentifier(Collections.singletonList(new Identifier().setValue(PATIENT_IDENTIFIER)));

        String studiesString = new Utils().readJSON("response/orthanc-studies-response.json");
        Exchange exchange = createExchange(studiesString, "c");

        Attachment attachment = new Attachment();
        attachment.setComment("http://localhost:8889/stone-webviewer/index.html?study=" + STUDY_ID);

        when(openmrsPatientHandler.getPatientByIdentifier(PATIENT_IDENTIFIER)).thenReturn(patient);
        when(orthancImagingStudyHandler.getSeriesByID(any(), any())).thenReturn(getSeries());
        when(orthancImagingStudyHandler.fetchStudyBinaryData(any())).thenReturn(new byte[] {});
        when(openmrsObsHandler.getObsByPatientUUIDAndConceptUUID(any(), any(), any()))
                .thenReturn(Collections.singletonList(attachment));

        // Act
        imagingStudyProcessor.process(exchange);

        // Assert
        assertEquals(exchange.getMessage().getHeader(HEADER_FHIR_EVENT_TYPE), "c");
        verify(orthancImagingStudyHandler, times(0)).fetchStudyBinaryData(any());
        verify(openmrsAttachmentHandler, times(0)).saveAttachment(any(), any(), any(), any());
    }

    @Test
    void shouldRecordTheStudyWithAFrenchNoteAndNoViewerLink() throws IOException {
        // Setup
        Patient patient = new Patient();
        patient.setId(PATIENT_ID);
        patient.setIdentifier(Collections.singletonList(new Identifier().setValue(PATIENT_IDENTIFIER)));

        String studiesString = new Utils().readJSON("response/orthanc-studies-response.json");
        Exchange exchange = createExchange(studiesString, "c");

        when(openmrsPatientHandler.getPatientByIdentifier(PATIENT_IDENTIFIER)).thenReturn(patient);
        when(orthancImagingStudyHandler.getSeriesByID(any(), any())).thenReturn(getSeries());
        when(orthancImagingStudyHandler.fetchStudyBinaryData(any())).thenReturn(new byte[] {});
        when(openmrsDiagnosticReportHandler.saveResult(any(), any(), any(), any(), any()))
                .thenReturn(RESULT_UUID);

        // Act
        imagingStudyProcessor.process(exchange);

        // Assert
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(openmrsDiagnosticReportHandler).saveResult(any(), eq(PATIENT_ID), any(), text.capture(), any());
        assertEquals(
                "\u00c9tude d'imagerie re\u00e7ue \u2013 ouvrir depuis la carte Imagerie (Pi\u00e8ces jointes).",
                text.getValue());
        assertFalse(text.getValue().contains("http"));
        assertFalse(text.getValue().contains("stone-webviewer"));
        // The Observation is still what the SR report and the orphan cleanup find the study by.
        verify(processedStudyRepository).save(ORTHANC_STUDY_ID, PATIENT_ID, RESULT_UUID);
    }

    @Test
    void shouldRecreateTheMissingAttachmentOfAnAlreadyProcessedStudy() throws IOException {
        // After an OpenMRS restore the bridge still has the study in eip_processed_orthanc_study,
        // but the attachment the Imagerie card lists is gone (UVL-EMR#578).
        givenAProcessedStudyOfThePatient();
        when(openmrsObsHandler.getObsByPatientUUIDAndConceptUUID(any(), eq(PATIENT_ID), any()))
                .thenReturn(Collections.emptyList());

        imagingStudyProcessor.process(createExchange(studies(), "c"));

        verify(openmrsAttachmentHandler, times(1))
                .saveAttachment(any(), eq(PATIENT_ID), eq(STUDY_ID), eq(ORTHANC_STUDY_ID));
        // Only the attachment: the study-received note and the tracking row are not written again.
        verify(openmrsDiagnosticReportHandler, never()).saveResult(any(), any(), any(), any(), any());
        verify(processedStudyRepository, never()).save(any(), any(), any());
    }

    @Test
    void shouldLeaveAnAlreadyProcessedStudyAloneWhenItsAttachmentExists() throws IOException {
        givenAProcessedStudyOfThePatient();
        when(openmrsObsHandler.getObsByPatientUUIDAndConceptUUID(any(), eq(PATIENT_ID), any()))
                .thenReturn(Collections.singletonList(attachmentOf(PATIENT_ID)));

        imagingStudyProcessor.process(createExchange(studies(), "c"));

        verify(openmrsObsHandler, times(1)).getObsByPatientUUIDAndConceptUUID(any(), eq(PATIENT_ID), any());
        verify(orthancImagingStudyHandler, never()).fetchStudyBinaryData(any());
        verify(openmrsAttachmentHandler, never()).saveAttachment(any(), any(), any(), any());
        verify(openmrsDiagnosticReportHandler, never()).saveResult(any(), any(), any(), any(), any());
    }

    @Test
    void shouldNotCountAnotherPatientsAttachmentWhenOpenmrsIgnoresThePatientFilter() throws IOException {
        givenAProcessedStudyOfThePatient();
        when(openmrsObsHandler.getObsByPatientUUIDAndConceptUUID(any(), eq(PATIENT_ID), any()))
                .thenReturn(Collections.singletonList(attachmentOf("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")));

        imagingStudyProcessor.process(createExchange(studies(), "c"));

        verify(openmrsAttachmentHandler, times(1))
                .saveAttachment(any(), eq(PATIENT_ID), eq(STUDY_ID), eq(ORTHANC_STUDY_ID));
    }

    @Test
    void shouldCheckAProcessedStudyAgainOnlyAfterTheRecheckInterval() throws IOException {
        givenAProcessedStudyOfThePatient();
        when(openmrsObsHandler.getObsByPatientUUIDAndConceptUUID(any(), eq(PATIENT_ID), any()))
                .thenReturn(Collections.singletonList(attachmentOf(PATIENT_ID)));
        Instant start = Instant.parse("2026-11-11T08:00:00Z");
        imagingStudyProcessor.setClock(Clock.fixed(start, ZoneOffset.UTC));

        imagingStudyProcessor.process(createExchange(studies(), "c"));
        imagingStudyProcessor.process(createExchange(studies(), "c"));
        verify(openmrsObsHandler, times(1)).getObsByPatientUUIDAndConceptUUID(any(), any(), any());

        imagingStudyProcessor.setClock(Clock.fixed(
                start.plus(ImagingStudyProcessor.ATTACHMENT_RECHECK_INTERVAL).plusSeconds(1), ZoneOffset.UTC));
        imagingStudyProcessor.process(createExchange(studies(), "c"));
        verify(openmrsObsHandler, times(2)).getObsByPatientUUIDAndConceptUUID(any(), any(), any());
    }

    @Test
    void shouldRetryAFailedAttachmentCheckOnTheNextPoll() throws IOException {
        givenAProcessedStudyOfThePatient();
        when(openmrsObsHandler.getObsByPatientUUIDAndConceptUUID(any(), eq(PATIENT_ID), any()))
                .thenThrow(new RuntimeException("OpenMRS is starting"))
                .thenReturn(Collections.emptyList());

        imagingStudyProcessor.process(createExchange(studies(), "c"));
        verify(openmrsAttachmentHandler, never()).saveAttachment(any(), any(), any(), any());

        imagingStudyProcessor.process(createExchange(studies(), "c"));
        verify(openmrsAttachmentHandler, times(1))
                .saveAttachment(any(), eq(PATIENT_ID), eq(STUDY_ID), eq(ORTHANC_STUDY_ID));
    }

    private void givenAProcessedStudyOfThePatient() throws IOException {
        Patient patient = new Patient();
        patient.setId(PATIENT_ID);
        patient.setIdentifier(Collections.singletonList(new Identifier().setValue(PATIENT_IDENTIFIER)));
        when(openmrsPatientHandler.getPatientByIdentifier(PATIENT_IDENTIFIER)).thenReturn(patient);
        when(processedStudyRepository.exists(ORTHANC_STUDY_ID)).thenReturn(true);
        when(orthancImagingStudyHandler.getSeriesByID(any(), any())).thenReturn(getSeries());
        when(orthancImagingStudyHandler.fetchStudyBinaryData(any())).thenReturn(new byte[] {1});
    }

    private static Attachment attachmentOf(String personUuid) {
        Attachment attachment = new Attachment();
        attachment.setComment("http://localhost:8889/stone-webviewer/index.html?study=" + STUDY_ID + "&orthancId="
                + ORTHANC_STUDY_ID);
        attachment.setPerson(new Attachment.Person(personUuid));
        return attachment;
    }

    private static String studies() {
        return new Utils().readJSON("response/orthanc-studies-response.json");
    }

    private Series getSeries() throws JsonProcessingException {
        String body = new Utils().readJSON("response/orthanc-series-response.json");
        Series[] series = objectMapper.readValue(body, Series[].class);
        return series[0];
    }
}
