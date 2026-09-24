/*
 * Copyright © 2024, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.openmrs.orthanc.processors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ozonehis.eip.openmrs.orthanc.RadiologyConcepts;
import com.ozonehis.eip.openmrs.orthanc.handlers.orthanc.OrthancWorklistHandler;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

@Slf4j
@Component
public class RadiologyOrderWorklistProcessor implements Processor {

    @Autowired
    private OrthancWorklistHandler orthancWorklistHandler;

    @Value("${openmrs.baseUrl:http://openmrs:8080/openmrs}")
    private String openmrsBaseUrl;

    @Value("${openmrs.username:admin}")
    private String openmrsUsername;

    @Value("${openmrs.password:Admin123}")
    private String openmrsPassword;

    // Radiology concept UUIDs - whitelist for worklist creation. Configurable through
    // RADIOLOGY_CONCEPT_UUIDS, shared with eip-odoo-openmrs; see RadiologyConcepts for the default.
    @Autowired
    private RadiologyConcepts radiologyConcepts;

    private final OkHttpClient httpClient = new OkHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * How far back each poll looks. A window, deliberately, rather than "since the last poll".
     *
     * <p>An order waits here until its payment Task appears, and payment can arrive hours or days
     * after the order is written. A since-last-poll cursor would show each order exactly once, in
     * the seconds after it was created and long before anyone has paid, and never look at it again
     * - the order would wait forever. The window keeps re-examining recent orders until they are
     * paid, and {@code processedRadiologyOrderRepository} stops the finished ones being redone.
     *
     * <p>Seven days is the practical ceiling on order-to-payment at UVL. Raising it costs one
     * larger FHIR page per poll; lowering it risks stranding an order that is paid late.
     */
    private static final int LOOKBACK_DAYS = 7;

    @Autowired
    private com.ozonehis.eip.openmrs.orthanc.repository.ProcessedRadiologyOrderRepository processedRadiologyOrderRepository;

    @Override
    public void process(Exchange exchange) throws Exception {
        String since = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneOffset.UTC)
            .format(Instant.now().minus(LOOKBACK_DAYS, ChronoUnit.DAYS));

        log.debug("Polling for radiology ServiceRequests since {}", since);

        try {
            // Bounded by _lastUpdated, NOT by _sort.
            //
            // This asked for "?_sort=-_lastUpdated&_count=100" and relied on the sort to put new
            // orders on the first page. OpenMRS's FHIR2 module does not honour _sort: it returns
            // the first 100 in insertion order, i.e. the OLDEST hundred. Measured on UAT with 830
            // ServiceRequests, an order placed the same minute was absent from the page, so no
            // order placed after the first hundred could ever reach the worklist - the automation
            // was silently dead rather than failing.
            //
            // _lastUpdated=gt is honoured, and the same query then returned 3 records including
            // the new one. It also keeps the page small: 830 records fetched every poll is the
            // reason this processor logged thousands of lines an hour.
            String url = openmrsBaseUrl + "/ws/fhir2/R4/ServiceRequest"
                + "?_lastUpdated=gt" + since + "&_count=100";

            JsonNode bundle = fetchFhir(url);
            if (bundle == null) return;

            log.debug("Found {} ServiceRequests updated since {}", bundle.path("entry").size(), since);
            int count = 0;
            for (JsonNode entry : bundle.path("entry")) {
                JsonNode sr = entry.path("resource");
                String srId = sr.path("id").asText("");
                String authored = sr.path("authoredOn").asText("");

                log.debug("Checking SR id={} status={} code_codings_size={} coding_isArray={}", 
                    srId, sr.path("status").asText(), 
                    sr.path("code").path("coding").size(),
                    sr.path("code").path("coding").isArray());
                if (processedRadiologyOrderRepository.exists(srId)) { log.debug("Already processed {}", srId); continue; }

                // Only process active orders
                String status = sr.path("status").asText("");
                if (!"active".equals(status)) { log.debug("Skipping non-active SR {} status={}", srId, status); continue; }

                // Filter by concept UUID whitelist, keeping the matched concept for the modality
                String radiologyConcept = null;
                for (JsonNode coding : sr.path("code").path("coding")) {
                    String conceptCode = coding.path("code").asText("");
                    log.debug("SR {} concept code: {}", srId.substring(0,8), conceptCode);
                    if (radiologyConcepts.contains(conceptCode)) {
                        radiologyConcept = conceptCode;
                        break;
                    }
                }
                if (radiologyConcept == null) {
                    log.info("Skipping non-radiology SR {}", srId.substring(0,8));
                    continue;
                }
                // Get patient info
                String patientRef = sr.path("subject").path("reference").asText("");
                String patientUuid = patientRef.contains("/") ? patientRef.split("/")[1] : patientRef;
                JsonNode patient = fetchFhir(openmrsBaseUrl + "/ws/fhir2/R4/Patient/" + patientUuid);
                if (patient == null) continue;

                // Patient identifier
                String patientId = patientUuid;
                for (JsonNode id : patient.path("identifier")) {
                    String val = id.path("value").asText("");
                    if (!val.isEmpty() && !val.equals(patientUuid)) {
                        patientId = val;
                        break;
                    }
                }

                // Patient name
                String family = patient.path("name").path(0).path("family").asText("");
                String given = patient.path("name").path(0).path("given").path(0).asText("");
                String dicomName = family + "^" + given;

                // Procedure
                String procedureDesc = sr.path("code").path("text").asText("Radiology");
                String accessionNumber = srId.replace("-", "").substring(0, 12).toUpperCase();

                // Modality from the concept; the name is only a fallback for an unmapped concept
                String modality = RadiologyConcepts.modalityFor(radiologyConcept, procedureDesc);

                // Check for a payment-confirmation Task (created by eip-odoo-openmrs's
                // RadiologyPaymentTaskProcessor once Odoo payment is confirmed) - only
                // create the worklist entry once one exists with status=accepted.
                // A missing Task means payment isn't confirmed yet; a Task with status
                // entered-in-error means the payment check itself failed and should
                // never be treated as a green light - both cases correctly skip here.
                if (!isPaymentConfirmedViaTask(srId)) {
                    log.debug("No payment Task found for ServiceRequest {} (patient {}, procedure '{}'), skipping worklist",
                        srId, patientUuid, procedureDesc);
                    continue;
                }

                try {
                    orthancWorklistHandler.createWorklistEntry(
                        patientId, dicomName, accessionNumber, procedureDesc, modality);
                    processedRadiologyOrderRepository.save(srId, accessionNumber);
                    count++;
                } catch (Exception e) {
                    log.error("Failed to create worklist for order {}: {}", srId, e.getMessage());
                }
            }

            if (count > 0) {
                log.info("Created {} Orthanc worklist entries from radiology orders", count);
            }
        } catch (Exception e) {
            log.error("Error polling radiology orders: {}", e.getMessage());
        }
    }

    /**
     * True only if THIS ServiceRequest has an accepted payment Task.
     *
     * <p>The search uses the typed form {@code ?based-on=ServiceRequest/<uuid>}. OpenMRS's FHIR2
     * module silently ignores the bare-uuid form {@code ?based-on=<uuid>} and returns every Task in
     * the system: measured on UAT, each such call took 0.46-0.54 s and returned all 22 Tasks, and
     * this poll repeats it for every active imaging order of the last seven days, so the bridges
     * held OpenMRS at a full core while idle. The typed form is honoured and returns only the
     * matching Task in 0.03-0.05 s.
     *
     * <p>The basedOn link is still checked here, in Java, and not left to the server. While the
     * search was ignored, trusting the server to filter meant that a single accepted Task -
     * anywhere, for any patient - authorised every radiology order. Measured on UAT: one accepted
     * Task produced 18 worklist entries for orders that had never been paid, across two patients.
     * That is a total bypass of the payment gate, so the filtering below stays whatever the server
     * does with the parameter.
     */
    private boolean isPaymentConfirmedViaTask(String serviceRequestId) {
        JsonNode bundle = fetchFhir(openmrsBaseUrl + "/ws/fhir2/R4/Task?based-on=ServiceRequest/" + serviceRequestId);
        if (bundle == null) {
            return false;
        }
        for (JsonNode entry : bundle.path("entry")) {
            JsonNode task = entry.path("resource");
            if (!"accepted".equals(task.path("status").asText(""))) {
                continue;
            }
            if (referencesServiceRequest(task, serviceRequestId)) {
                return true;
            }
            log.debug("Ignoring accepted Task {} - it is not based on ServiceRequest {}",
                task.path("id").asText(""), serviceRequestId);
        }
        return false;
    }

    /**
     * Whether a Task's basedOn actually points at the given ServiceRequest.
     *
     * <p>basedOn is 0..* in FHIR, so it is normally an array, but a server is free to emit a single
     * object - both shapes are handled rather than assumed. References are compared on the trailing
     * id so that "ServiceRequest/<uuid>", a bare "<uuid>" and an absolute URL all match.
     */
    private boolean referencesServiceRequest(JsonNode task, String serviceRequestId) {
        JsonNode basedOn = task.path("basedOn");
        for (JsonNode ref : basedOn.isArray() ? basedOn : java.util.Collections.singletonList(basedOn)) {
            String reference = ref.path("reference").asText("");
            if (reference.isEmpty()) {
                continue;
            }
            String id = reference.substring(reference.lastIndexOf('/') + 1);
            if (serviceRequestId.equals(id)) {
                return true;
            }
        }
        return false;
    }

    private JsonNode fetchFhir(String url) {
        try {
            Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString((openmrsUsername + ":" + openmrsPassword).getBytes()))
                .build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) return null;
                return mapper.readTree(response.body().string());
            }
        } catch (Exception e) {
            log.warn("Failed to fetch {}: {}", url, e.getMessage());
            return null;
        }
    }
}
