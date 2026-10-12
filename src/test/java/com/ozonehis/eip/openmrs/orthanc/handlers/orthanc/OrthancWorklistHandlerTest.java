/*
 * Copyright © 2024, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.openmrs.orthanc.handlers.orthanc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ozonehis.eip.openmrs.orthanc.config.OrthancConfig;
import com.ozonehis.eip.openmrs.orthanc.config.OrthancTokenProvider;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The worklist entry names the modality's station, not Orthanc, and carries the modality it is given
 * (UVL-EMR#424). Runs against a local HTTP server standing in for Orthanc, so the assertions are on
 * the JSON actually posted to /worklists/create.
 */
class OrthancWorklistHandlerTest {

    private HttpServer server;

    private JsonNode posted;

    private OrthancWorklistHandler handler;

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/worklists/create", exchange -> {
            posted = new ObjectMapper().readTree(exchange.getRequestBody());
            respond(exchange, "{\"ID\":\"wl-1\"}");
        });
        server.createContext("/worklists", exchange -> respond(exchange, "[]"));
        server.start();

        OrthancConfig config = new OrthancConfig();
        config.setOrthancBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        OrthancTokenProvider tokenProvider = mock(OrthancTokenProvider.class);
        when(tokenProvider.getToken()).thenReturn("token");

        handler = new OrthancWorklistHandler();
        ReflectionTestUtils.setField(handler, "orthancConfig", config);
        ReflectionTestUtils.setField(handler, "orthancTokenProvider", tokenProvider);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void shouldLabelTheEntryWithTheConfiguredStationAeTitle() throws Exception {
        ReflectionTestUtils.setField(handler, "stationAeTitle", "XMARUM");

        handler.createWorklistEntry("UVL-0001", "Doe^Jane", "5F0C9B522C8E", "RX01 - Chest X-ray", "DX");

        JsonNode step = posted.path("Tags").path("ScheduledProcedureStepSequence").path(0);
        assertEquals("XMARUM", step.path("ScheduledStationAETitle").asText());
        assertEquals("DX", step.path("Modality").asText());
    }

    @Test
    void shouldKeepOrthancAsTheStationWhenNothingIsConfigured() throws Exception {
        handler.createWorklistEntry("UVL-0001", "Doe^Jane", "5F0C9B522C8E", "RX01 - Chest X-ray", "DX");

        assertEquals("ORTHANC", posted.path("Tags").path("ScheduledProcedureStepSequence").path(0)
                .path("ScheduledStationAETitle").asText());
    }

    @Test
    void shouldTreatABlankStationAeTitleAsUnset() throws Exception {
        ReflectionTestUtils.setField(handler, "stationAeTitle", "   ");

        handler.createWorklistEntry("UVL-0001", "Doe^Jane", "5F0C9B522C8E", "RX01 - Chest X-ray", "DX");

        assertEquals("ORTHANC", posted.path("Tags").path("ScheduledProcedureStepSequence").path(0)
                .path("ScheduledStationAETitle").asText());
    }

    @Test
    void theEnvironmentVariableOrthancWorklistStationAetResolvesTheProperty() {
        // How the value reaches the container: compose sets ORTHANC_WORKLIST_STATION_AET, and
        // Spring's environment lookup maps it onto orthanc.worklist.station.aet.
        StandardEnvironment environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .replace(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new SystemEnvironmentPropertySource(
                                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                Collections.<String, Object>singletonMap("ORTHANC_WORKLIST_STATION_AET", "XMARUM")));

        String placeholder = "${" + OrthancWorklistHandler.STATION_AET_PROPERTY + ":"
                + OrthancWorklistHandler.DEFAULT_STATION_AET + "}";

        assertEquals("XMARUM", environment.resolvePlaceholders(placeholder));
        assertEquals("ORTHANC", new StandardEnvironment().resolvePlaceholders(placeholder));
    }

    private static void respond(HttpExchange exchange, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
