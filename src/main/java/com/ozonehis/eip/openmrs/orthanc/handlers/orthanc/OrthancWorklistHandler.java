/*
 * Copyright © 2024, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.openmrs.orthanc.handlers.orthanc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ozonehis.eip.openmrs.orthanc.config.OrthancConfig;
import com.ozonehis.eip.openmrs.orthanc.config.OrthancTokenProvider;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

@Slf4j
@Component
public class OrthancWorklistHandler {

    @Autowired
    private OrthancConfig orthancConfig;

    @Autowired
    private OrthancTokenProvider orthancTokenProvider;

    /** Used when {@value #STATION_AET_PROPERTY} is unset or blank: the value hard-coded before UVL-EMR#424. */
    public static final String DEFAULT_STATION_AET = "ORTHANC";

    /** Spring maps the environment variable ORTHANC_WORKLIST_STATION_AET onto this property. */
    public static final String STATION_AET_PROPERTY = "orthanc.worklist.station.aet";

    /**
     * The AE title written as ScheduledStationAETitle - the MODALITY expected to perform the step,
     * not Orthanc (UVL-EMR#424).
     *
     * <p>This was the literal "ORTHANC", the name of the PACS. A modality asks for its worklist
     * filtered on its own AE title, and DICOM matching compares that with the value in each item,
     * so every entry this bridge wrote was invisible to the X-ray unit. Measured by C-FIND on UAT on
     * 7 Aug 2026 with two entries present: ScheduledStationAETitle=XMARU returned only the entry
     * labelled XMARU, an empty value returned both. A patch reading ORTHANC_WORKLIST_STATION_AET was
     * shipped in a jar that day but never committed here, so every jar built since went back to
     * ORTHANC while the compose file kept setting a variable nothing read.
     *
     * <p>The Mugamba unit's AE title is XMARUM, as photographed on the unit itself; the deployment
     * sets it.
     * Unset or blank keeps ORTHANC, so a site without the variable behaves as before.
     */
    @Value("${" + STATION_AET_PROPERTY + ":" + DEFAULT_STATION_AET + "}")
    private String stationAeTitle = DEFAULT_STATION_AET;

    private final OkHttpClient httpClient = new OkHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Creates a worklist entry for an order, unless one already exists for the same accession.
     *
     * <p>The caller's own "have I done this?" check is a row in eip_processed_radiology_order, which
     * makes the database the sole guard against duplicates. Anything that clears or diverges from
     * that table - a reset, a restore, a fresh deployment onto an existing worklists volume - makes
     * every order look new again while the .wl files are still sitting there, and each one is
     * written a second time. Observed on UAT after the table was cleared to re-test the payment
     * gate: four orders gained a second entry, with today's scheduled date rather than their own.
     *
     * <p>That matters at the modality, not just in the database: a duplicated accession number shows
     * the technician the same study twice with no way to tell which to choose.
     *
     * <p>So Orthanc itself is asked first. The repository row stays as the cheap path; this is the
     * check that actually holds when it is wrong.
     */
    public String createWorklistEntry(String patientId, String patientName,
                                       String accessionNumber, String procedureDesc,
                                       String modality) throws IOException {
        String existingId = findWorklistEntryByAccession(accessionNumber);
        if (existingId != null) {
            log.info("Worklist entry already exists for accession {} ({}), not creating a duplicate",
                accessionNumber, existingId);
            return existingId;
        }

        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String now = LocalTime.now().format(DateTimeFormatter.ofPattern("HHmmss"));

        ObjectNode tags = mapper.createObjectNode();
        tags.put("PatientID", patientId);
        tags.put("PatientName", patientName != null ? patientName : patientId);
        tags.put("AccessionNumber", accessionNumber);
        tags.put("RequestedProcedureDescription", procedureDesc != null ? procedureDesc : "Radiology");
        tags.put("RequestedProcedureID", accessionNumber);

        ArrayNode stepSequence = mapper.createArrayNode();
        ObjectNode step = mapper.createObjectNode();
        step.put("Modality", modality != null ? modality : "XR");
        step.put("ScheduledProcedureStepStartDate", today);
        step.put("ScheduledProcedureStepStartTime", now);
        step.put("ScheduledStationAETitle", stationAeTitle());
        step.put("ScheduledProcedureStepID", accessionNumber);
        step.put("ScheduledProcedureStepDescription", procedureDesc != null ? procedureDesc : "Radiology");
        stepSequence.add(step);
        tags.set("ScheduledProcedureStepSequence", stepSequence);

        ObjectNode payload = mapper.createObjectNode();
        payload.set("Tags", tags);

        String url = orthancConfig.getOrthancBaseUrl() + "/worklists/create";
        String token = orthancTokenProvider.getToken();

        Request request = new Request.Builder()
            .url(url)
            .header("token", token)
            .post(RequestBody.create(
                mapper.writeValueAsString(payload),
                MediaType.parse("application/json")))
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("Failed to create Orthanc worklist: " + body);
            }
            String id = mapper.readTree(body).path("ID").asText();
            log.info("Created Orthanc worklist entry {} for patient {} accession {}",
                id, patientId, accessionNumber);
            return id;
        }
    }

    /** The configured station AE title, trimmed; ORTHANC if it is blank. */
    String stationAeTitle() {
        String aet = stationAeTitle == null ? "" : stationAeTitle.trim();
        return aet.isEmpty() ? DEFAULT_STATION_AET : aet;
    }

    /**
     * The id of the existing worklist entry for this accession, or null if there is none.
     *
     * <p>A failure to read the list returns null rather than throwing: not being able to check is
     * not evidence that an entry exists, and refusing to create the entry would turn a transient
     * Orthanc hiccup into an order that never reaches the modality. The duplicate this guards
     * against is the lesser harm of the two.
     */
    private String findWorklistEntryByAccession(String accessionNumber) {
        if (accessionNumber == null || accessionNumber.isEmpty()) {
            return null;
        }
        Request request = new Request.Builder()
            .url(orthancConfig.getOrthancBaseUrl() + "/worklists")
            .header("token", orthancTokenProvider.getToken())
            .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.warn("Could not list Orthanc worklists ({}), proceeding without a duplicate check",
                    response.code());
                return null;
            }
            for (com.fasterxml.jackson.databind.JsonNode entry : mapper.readTree(response.body().string())) {
                if (accessionNumber.equals(entry.path("Tags").path("AccessionNumber").asText(""))) {
                    return entry.path("ID").asText(null);
                }
            }
        } catch (IOException e) {
            log.warn("Could not list Orthanc worklists ({}), proceeding without a duplicate check",
                e.getMessage());
        }
        return null;
    }
}
