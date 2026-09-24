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
import static org.mockito.Mockito.verify;

import com.ozonehis.eip.openmrs.orthanc.RadiologyConcepts;
import com.ozonehis.eip.openmrs.orthanc.handlers.orthanc.OrthancWorklistHandler;
import com.ozonehis.eip.openmrs.orthanc.repository.ProcessedRadiologyOrderRepository;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Runs the processor against a local HTTP server standing in for OpenMRS, so the assertions are on
 * the Task query that actually goes over the wire (UVL-EMR#301).
 */
class RadiologyOrderWorklistProcessorTest {

    private static final String RX01 = "e3dea2c8-62c6-4487-bdaa-1d009642f7ad";

    private static final String SERVICE_REQUEST_ID = "5f0c9b52-2c8e-4a39-9d2f-8a1b3c4d5e6f";

    private static final String OTHER_SERVICE_REQUEST_ID = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d";

    private static final String PATIENT_ID = "9b1f2c3d-4e5f-4a6b-8c7d-0e1f2a3b4c5d";

    private HttpServer server;

    private final List<String> taskQueries = new ArrayList<>();

    private String taskBundle;

    private OrthancWorklistHandler orthancWorklistHandler;

    private RadiologyOrderWorklistProcessor processor;

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/openmrs/ws/fhir2/R4/ServiceRequest", exchange -> respond(exchange, serviceRequestBundle()));
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
                .createWorklistEntry(anyString(), anyString(), anyString(), anyString(), eq("XR"));
    }

    @Test
    void shouldNotCreateWorklistEntryWhenOnlyAnotherOrdersTaskIsAccepted() throws Exception {
        taskBundle = bundleOf(acceptedTask("t-other", OTHER_SERVICE_REQUEST_ID));

        processor.process(null);

        verify(orthancWorklistHandler, never())
                .createWorklistEntry(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String json)
            throws java.io.IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/fhir+json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static String serviceRequestBundle() {
        return "{\"resourceType\":\"Bundle\",\"type\":\"searchset\",\"entry\":[{\"resource\":{"
                + "\"resourceType\":\"ServiceRequest\",\"id\":\"" + SERVICE_REQUEST_ID + "\","
                + "\"status\":\"active\",\"authoredOn\":\"2026-09-24T08:00:00+00:00\","
                + "\"code\":{\"text\":\"Chest X-ray\",\"coding\":[{\"code\":\"" + RX01 + "\"}]},"
                + "\"subject\":{\"reference\":\"Patient/" + PATIENT_ID + "\"}}}]}";
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
        StringBuilder entries = new StringBuilder();
        for (String resource : resources) {
            if (entries.length() > 0) {
                entries.append(',');
            }
            entries.append("{\"resource\":").append(resource).append('}');
        }
        return "{\"resourceType\":\"Bundle\",\"type\":\"searchset\",\"entry\":[" + entries + "]}";
    }
}
