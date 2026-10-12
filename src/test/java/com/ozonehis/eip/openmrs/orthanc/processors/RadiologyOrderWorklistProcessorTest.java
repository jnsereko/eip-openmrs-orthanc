/*
 * Copyright © 2024, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.openmrs.orthanc.processors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.ozonehis.eip.openmrs.orthanc.RadiologyConcepts;
import com.ozonehis.eip.openmrs.orthanc.handlers.orthanc.OrthancWorklistHandler;
import com.ozonehis.eip.openmrs.orthanc.repository.ProcessedRadiologyOrderRepository;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Runs the processor against a local HTTP server standing in for OpenMRS, so the assertions are on
 * the Task query that actually goes over the wire (UVL-EMR#301), and on the pages of the
 * ServiceRequest search it actually reads (UVL-EMR#424).
 */
class RadiologyOrderWorklistProcessorTest {

    private static final String RX01 = "e3dea2c8-62c6-4487-bdaa-1d009642f7ad";

    private static final String EC01 = "8155e2e0-5b62-42bc-b47c-0702aaafe3df";

    private static final String SERVICE_REQUEST_ID = "5f0c9b52-2c8e-4a39-9d2f-8a1b3c4d5e6f";

    private static final String OTHER_SERVICE_REQUEST_ID = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d";

    private static final String PATIENT_ID = "9b1f2c3d-4e5f-4a6b-8c7d-0e1f2a3b4c5d";

    /** A lab order concept: active, in the window, not radiology. */
    private static final String LAB_CONCEPT = "1015AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    /**
     * What OpenMRS FHIR2 writes as a next link: the server base it believes it has (here a public
     * host the bridge cannot use) plus the cached-search query.
     */
    private static final String FOREIGN_BASE = "https://emr.example.org/openmrs/ws/fhir2/R4";

    private final List<String> pageQueries = new ArrayList<>();

    /** Serves page n (1-based) of the ServiceRequest search when set; null serves the single page. */
    private IntFunction<String> pages;

    private HttpServer server;

    private final List<String> taskQueries = new ArrayList<>();

    private String taskBundle;

    private String srConcept = RX01;

    private String srText = "Chest X-ray";

    private OrthancWorklistHandler orthancWorklistHandler;

    private RadiologyOrderWorklistProcessor processor;

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/openmrs/ws/fhir2/R4/ServiceRequest", exchange -> respond(exchange,
                pages != null ? pages.apply(1) : serviceRequestBundle(srConcept, srText)));
        // Where a next link leads once rebased: /ws/fhir2/R4?_getpages=...&_getpagesoffset=<n*100>
        server.createContext("/openmrs/ws/fhir2/R4", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            pageQueries.add(query);
            int offset = Integer.parseInt(query.replaceAll(".*_getpagesoffset=(\\d+).*", "$1"));
            respond(exchange, pages.apply(offset / RadiologyOrderWorklistProcessor.PAGE_SIZE + 1));
        });
        server.createContext("/openmrs/ws/fhir2/R4/Patient/", exchange -> respond(exchange, patient()));
        server.createContext("/openmrs/ws/fhir2/R4/Task", exchange -> {
            taskQueries.add(exchange.getRequestURI().getRawQuery());
            respond(exchange, taskBundle);
        });
        server.start();

        orthancWorklistHandler = mock(OrthancWorklistHandler.class);
        processor = new RadiologyOrderWorklistProcessor();
        ReflectionTestUtils.setField(
                processor, "openmrsBaseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/openmrs");
        ReflectionTestUtils.setField(processor, "openmrsUsername", "admin");
        ReflectionTestUtils.setField(processor, "openmrsPassword", "secret");
        ReflectionTestUtils.setField(processor, "radiologyConcepts", new RadiologyConcepts());
        ReflectionTestUtils.setField(processor, "orthancWorklistHandler", orthancWorklistHandler);
        ReflectionTestUtils.setField(
                processor, "processedRadiologyOrderRepository", mock(ProcessedRadiologyOrderRepository.class));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void shouldSearchTasksWithTypedServiceRequestReference() throws Exception {
        taskBundle = bundleOf(acceptedTask("t-mine", SERVICE_REQUEST_ID));

        processor.process(null);

        assertEquals(List.of("based-on=ServiceRequest/" + SERVICE_REQUEST_ID), taskQueries);
        verify(orthancWorklistHandler)
                .createWorklistEntry(anyString(), anyString(), anyString(), anyString(), eq("DX"));
    }

    @Test
    void shouldScheduleAnXrayWithTheConfiguredXrayModality() throws Exception {
        // UVL-EMR#424: XR is not what xmaru calls an X-ray; DX is the default, and it is configurable.
        ReflectionTestUtils.setField(processor, "radiologyConcepts", new RadiologyConcepts(null, "cr"));
        taskBundle = bundleOf(acceptedTask("t-mine", SERVICE_REQUEST_ID));

        processor.process(null);

        verify(orthancWorklistHandler)
                .createWorklistEntry(anyString(), anyString(), anyString(), anyString(), eq("CR"));
    }

    @Test
    void shouldFollowNextLinksToAPaidXrayBeyondTheFirstPage() throws Exception {
        // UVL-EMR#424: a full first page of lab orders, the paid X-ray on page three. Only the first
        // page was read, so the X-ray waited until the orders before it left the seven-day window.
        pages = page -> page < 3
                ? labPage(page, FOREIGN_BASE + "?_getpages=abc&_getpagesoffset=" + page * 100 + "&_count=100")
                : bundleOf(new String[] {serviceRequest(SERVICE_REQUEST_ID, RX01, "Chest X-ray")}, null);
        taskBundle = bundleOf(acceptedTask("t-mine", SERVICE_REQUEST_ID));

        processor.process(null);

        assertEquals(
                List.of("_getpages=abc&_getpagesoffset=100&_count=100", "_getpages=abc&_getpagesoffset=200&_count=100"),
                pageQueries,
                "both next links are followed, and rebased onto openmrsBaseUrl rather than the host they name");
        assertEquals(List.of("based-on=ServiceRequest/" + SERVICE_REQUEST_ID), taskQueries);
        verify(orthancWorklistHandler)
                .createWorklistEntry(anyString(), anyString(), anyString(), anyString(), eq("DX"));
    }

    @Test
    void shouldStopWhenANextLinkRepeatsAPageAlreadyRead() throws Exception {
        // Page 2 links back to itself: read once, then stop - and its X-ray is still scheduled.
        pages = page -> page == 1
                ? labPage(page, FOREIGN_BASE + "?_getpages=abc&_getpagesoffset=100&_count=100")
                : bundleOf(new String[] {serviceRequest(SERVICE_REQUEST_ID, RX01, "Chest X-ray")},
                        FOREIGN_BASE + "?_getpages=abc&_getpagesoffset=100&_count=100");
        taskBundle = bundleOf(acceptedTask("t-mine", SERVICE_REQUEST_ID));

        processor.process(null);

        assertEquals(1, pageQueries.size());
        verify(orthancWorklistHandler)
                .createWorklistEntry(anyString(), anyString(), anyString(), anyString(), eq("DX"));
    }

    @Test
    void shouldStopFollowingNextLinksAfterTheMaximumPageCount() throws Exception {
        // A server that never stops offering a next page: the poll reads MAX_PAGES and returns.
        pages = page -> labPage(page, FOREIGN_BASE + "?_getpages=abc&_getpagesoffset=" + page * 100 + "&_count=100");

        processor.process(null);

        assertEquals(RadiologyOrderWorklistProcessor.MAX_PAGES - 1, pageQueries.size());
        verify(orthancWorklistHandler, never())
                .createWorklistEntry(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void shouldKeepThePagesAlreadyReadWhenALaterPageFails() throws Exception {
        // The X-ray is on page 1; page 2 is a 404. The X-ray must still be scheduled.
        pages = page -> bundleOf(new String[] {serviceRequest(SERVICE_REQUEST_ID, RX01, "Chest X-ray")},
                "http://127.0.0.1:1/openmrs/ws/fhir2/R4/Nowhere?_getpages=gone");
        server.createContext("/openmrs/ws/fhir2/R4/Nowhere", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        taskBundle = bundleOf(acceptedTask("t-mine", SERVICE_REQUEST_ID));

        processor.process(null);

        verify(orthancWorklistHandler, times(1))
                .createWorklistEntry(anyString(), anyString(), anyString(), anyString(), eq("DX"));
    }

    @Test
    void shouldRebaseNextLinksOntoTheOpenmrsBaseUrl() {
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/openmrs";

        assertEquals(base + "/ws/fhir2/R4?_getpages=abc&_getpagesoffset=100",
                processor.rebaseOnOpenmrs("https://emr.example.org/openmrs/ws/fhir2/R4?_getpages=abc&_getpagesoffset=100"));
        assertEquals(base + "/ws/fhir2/R4?_getpages=abc",
                processor.rebaseOnOpenmrs("http://openmrs:8080/openmrs/ws/fhir2/R4?_getpages=abc"));
        assertEquals(base + "/ws/fhir2/R4?_getpages=abc", processor.rebaseOnOpenmrs("/openmrs/ws/fhir2/R4?_getpages=abc"));
        assertEquals(base + "/ws/fhir2/R4?_getpages=abc", processor.rebaseOnOpenmrs("?_getpages=abc"));
    }

    @Test
    void shouldNotCreateWorklistEntryWhenOnlyAnotherOrdersTaskIsAccepted() throws Exception {
        taskBundle = bundleOf(acceptedTask("t-other", OTHER_SERVICE_REQUEST_ID));

        processor.process(null);

        verify(orthancWorklistHandler, never())
                .createWorklistEntry(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void shouldScheduleAnUltrasoundOrderWithModalityUs() throws Exception {
        // UVL-EMR#304: EC01 was not a radiology concept, and its name has no "ultrasound" in French.
        srConcept = EC01;
        srText = "Échographie abdominale";
        taskBundle = bundleOf(acceptedTask("t-mine", SERVICE_REQUEST_ID));

        processor.process(null);

        verify(orthancWorklistHandler)
                .createWorklistEntry(anyString(), anyString(), anyString(), eq("Échographie abdominale"), eq("US"));
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String json)
            throws java.io.IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/fhir+json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static String serviceRequestBundle(String concept, String text) {
        return bundleOf(new String[] {serviceRequest(SERVICE_REQUEST_ID, concept, text)}, null);
    }

    private static String serviceRequest(String id, String concept, String text) {
        return "{\"resourceType\":\"ServiceRequest\",\"id\":\"" + id + "\","
                + "\"status\":\"active\",\"authoredOn\":\"2026-09-24T08:00:00+00:00\","
                + "\"code\":{\"text\":\"" + text + "\",\"coding\":[{\"code\":\"" + concept + "\"}]},"
                + "\"subject\":{\"reference\":\"Patient/" + PATIENT_ID + "\"}}";
    }

    /** A full page of PAGE_SIZE active lab orders, linking to {@code next}. */
    private static String labPage(int page, String next) {
        String[] labOrders = new String[RadiologyOrderWorklistProcessor.PAGE_SIZE];
        for (int i = 0; i < labOrders.length; i++) {
            String id = String.format("%08d-0000-4000-8000-%012d", page, i);
            labOrders[i] = serviceRequest(id, LAB_CONCEPT, "Hemoglobin");
        }
        return bundleOf(labOrders, next);
    }

    private static String patient() {
        return "{\"resourceType\":\"Patient\",\"id\":\"" + PATIENT_ID + "\","
                + "\"identifier\":[{\"value\":\"UVL-0001\"}],"
                + "\"name\":[{\"family\":\"Doe\",\"given\":[\"Jane\"]}]}";
    }

    private static String acceptedTask(String id, String serviceRequestId) {
        return "{\"resourceType\":\"Task\",\"id\":\"" + id + "\",\"status\":\"accepted\",\"intent\":\"order\","
                + "\"basedOn\":[{\"reference\":\"ServiceRequest/" + serviceRequestId + "\"}]}";
    }

    private static String bundleOf(String... resources) {
        return bundleOf(resources, null);
    }

    private static String bundleOf(String[] resources, String next) {
        StringBuilder entries = new StringBuilder();
        for (String resource : resources) {
            if (entries.length() > 0) {
                entries.append(',');
            }
            entries.append("{\"resource\":").append(resource).append('}');
        }
        String link = next == null ? "" : "\"link\":[{\"relation\":\"self\",\"url\":\"" + FOREIGN_BASE
                + "/ServiceRequest\"},{\"relation\":\"next\",\"url\":\"" + next + "\"}],";
        return "{\"resourceType\":\"Bundle\",\"type\":\"searchset\"," + link + "\"entry\":[" + entries + "]}";
    }
}
