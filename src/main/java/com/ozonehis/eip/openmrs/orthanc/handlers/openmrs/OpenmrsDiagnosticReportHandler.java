/*
 * Copyright © 2024, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.openmrs.orthanc.handlers.openmrs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ozonehis.eip.openmrs.orthanc.Constants;
import com.ozonehis.eip.openmrs.orthanc.config.OpenmrsConfig;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.ProducerTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@Slf4j
@Component
public class OpenmrsDiagnosticReportHandler {

    private static final DateTimeFormatter OBS_DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

    private static final OkHttpClient HTTP = new OkHttpClient();

    @Autowired
    private OpenmrsConfig openmrsConfig;

    @Autowired
    private OpenmrsRestDeleteHelper deleteHelper;

    @Value("${openmrs.baseUrl}")
    private String openmrsBaseUrl;

    /**
     * Creates a plain Observation carrying a result/note for a patient,
     * via OpenMRS's native REST API - the same simple pattern already
     * proven by saveAttachment(). Confirmed via live testing that this
     * requires no Encounter and displays correctly in O3's Results tab,
     * replacing the old createDiagnosticReport() + Encounter + FHIR
     * DiagnosticReport chain, whose conclusion/presentedForm/result
     * fields never persisted in OpenMRS anyway.
     *
     * @return the new Observation's UUID, or null if creation failed
     */
    public String saveResult(
            ProducerTemplate producerTemplate,
            String patientUUID,
            String conceptUUID,
            String value,
            String obsDate) throws JsonProcessingException {

        String effectiveDate = obsDate != null && obsDate.length() == 8
                ? obsDate.substring(0, 4) + "-" + obsDate.substring(4, 6) + "-" + obsDate.substring(6, 8)
                : java.time.LocalDate.now().toString();

        String obsJson = String.format(
                "{\"person\":\"%s\"," +
                "\"concept\":\"%s\"," +
                "\"value\":\"%s\"," +
                "\"obsDatetime\":\"%s\"}",
                patientUUID,
                conceptUUID,
                value.replace("\"", "\\\"").replace("\n", "\\n"),
                effectiveDate);

        Map<String, Object> headers = new HashMap<>();
        headers.put(Constants.CAMEL_HTTP_METHOD, Constants.POST);
        headers.put(Constants.CONTENT_TYPE, Constants.APPLICATION_JSON);
        headers.put(Constants.AUTHORIZATION, openmrsConfig.authHeader());

        String response = producerTemplate.requestBodyAndHeaders(
                "direct:openmrs-create-obs-native-route", obsJson, headers, String.class);

        String obsUUID = null;
        try {
            Map<?, ?> result = new ObjectMapper().readValue(response, Map.class);
            obsUUID = (String) result.get("uuid");
            log.info("saveResult: created Observation {} for patient {}", obsUUID, patientUUID);
        } catch (Exception e) {
            log.warn("saveResult: could not parse Observation UUID from response: {}", e.getMessage());
        }
        return obsUUID;
    }

    /**
     * Records SR (Structured Report) content as a result Observation for a
     * patient. Simplified from the original design, which tried to find and
     * update an existing Observation linked via DiagnosticReport.result -
     * that field never persists in OpenMRS, so the lookup always failed and
     * a new Observation was always created anyway (see ProcessedSRRepository
     * for the separate mechanism that now prevents duplicate processing of
     * the same SR instance). This goes straight to creating the result,
     * using the exact ordered procedure's concept when resolved via
     * AccessionNumber, falling back to the generic note concept otherwise.
     *
     * <p>When the order is known, the Observation is filed against that order
     * and its encounter, and the order's fulfiller status is set to COMPLETED,
     * so the chart shows the report as the order's result and the order as done
     * (UVL-EMR#427). The time is when the report reached OpenMRS, not midnight.
     */
    public String updateDiagnosticReportWithSR(
            ProducerTemplate producerTemplate,
            String patientUUID,
            String srText,
            String procedureConceptUuid,
            String orderUuid) throws JsonProcessingException {

        boolean hasOrder = orderUuid != null && !orderUuid.isEmpty();
        String observationConceptUuid = (procedureConceptUuid != null && !procedureConceptUuid.isEmpty())
                ? procedureConceptUuid : Constants.GENERAL_PATIENT_NOTE_CONCEPT_UUID;

        Map<String, Object> obs = new LinkedHashMap<>();
        obs.put("person", patientUUID);
        obs.put("concept", observationConceptUuid);
        obs.put("value", srText);
        // A few seconds back so a clock a little ahead of OpenMRS never trips "obs date in the future"
        obs.put("obsDatetime", OBS_DATETIME.format(ZonedDateTime.now(ZoneOffset.UTC).minusSeconds(10)));
        if (hasOrder) {
            obs.put("order", orderUuid);
            String encounterUuid = getOrderEncounterUuid(orderUuid);
            if (encounterUuid != null) {
                obs.put("encounter", encounterUuid);
            }
        }

        String resultUUID = createObs(producerTemplate, new ObjectMapper().writeValueAsString(obs), patientUUID);
        log.info("updateDiagnosticReportWithSR: recorded SR result {} for patient {} (order {})",
                resultUUID, patientUUID, hasOrder ? orderUuid : "none");

        if (hasOrder && resultUUID != null) {
            markOrderCompleted(orderUuid);
        }
        return resultUUID;
    }

    private String createObs(ProducerTemplate producerTemplate, String obsJson, String patientUUID) {
        Map<String, Object> headers = new HashMap<>();
        headers.put(Constants.CAMEL_HTTP_METHOD, Constants.POST);
        headers.put(Constants.CONTENT_TYPE, Constants.APPLICATION_JSON);
        headers.put(Constants.AUTHORIZATION, openmrsConfig.authHeader());

        String response = producerTemplate.requestBodyAndHeaders(
                "direct:openmrs-create-obs-native-route", obsJson, headers, String.class);
        try {
            Map<?, ?> result = new ObjectMapper().readValue(response, Map.class);
            return (String) result.get("uuid");
        } catch (Exception e) {
            log.warn("createObs: could not parse Observation UUID for patient {}: {}", patientUUID, e.getMessage());
            return null;
        }
    }

    private String getOrderEncounterUuid(String orderUuid) {
        Request request = new Request.Builder()
                .url(openmrsBaseUrl + "/ws/rest/v1/order/" + orderUuid + "?v=custom:(uuid,encounter:(uuid))")
                .header("Authorization", openmrsConfig.authHeader())
                .build();
        try (Response response = HTTP.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                log.warn("Could not read order {}: HTTP {}", orderUuid, response.code());
                return null;
            }
            String uuid = new ObjectMapper().readTree(response.body().string())
                    .path("encounter").path("uuid").asText("");
            return uuid.isEmpty() ? null : uuid;
        } catch (Exception e) {
            log.warn("Could not read the encounter of order {}: {}", orderUuid, e.getMessage());
            return null;
        }
    }

    /**
     * Failure here is logged, not thrown: the report is already in the chart,
     * and a retry would record it a second time.
     */
    private void markOrderCompleted(String orderUuid) {
        RequestBody body = RequestBody.create(
                "{\"fulfillerStatus\":\"COMPLETED\",\"fulfillerComment\":\"Radiology report received from PACS\"}",
                MediaType.get("application/json"));
        Request request = new Request.Builder()
                .url(openmrsBaseUrl + "/ws/rest/v1/order/" + orderUuid + "/fulfillerdetails")
                .header("Authorization", openmrsConfig.authHeader())
                .post(body)
                .build();
        try (Response response = HTTP.newCall(request).execute()) {
            if (response.isSuccessful()) {
                log.info("Marked order {} COMPLETED", orderUuid);
            } else {
                log.warn("Could not mark order {} COMPLETED: HTTP {}", orderUuid, response.code());
            }
        } catch (Exception e) {
            log.warn("Could not mark order {} COMPLETED: {}", orderUuid, e.getMessage());
        }
    }

    /**
     * Deletes a result Observation created by saveResult(), using the shared
     * purge=true delete pattern (matching the pattern already proven
     * necessary for attachments in OpenmrsAttachmentHandler.deleteAttachment()) -
     * a plain DELETE only voids the record without actually removing it
     * (confirmed via live testing: GET still returns 200 with full data
     * after a non-purge delete).
     */
    public boolean deleteResult(String observationUUID) throws java.io.IOException {
        return deleteHelper.deleteByUuid("obs", observationUUID, "result Observation");
    }
}
