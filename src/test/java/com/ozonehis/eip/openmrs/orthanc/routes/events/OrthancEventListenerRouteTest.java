/*
 * Copyright © 2024, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.openmrs.orthanc.routes.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.apache.camel.Exchange;
import org.apache.camel.RoutesBuilder;
import org.apache.camel.test.junit5.CamelTestSupport;
import org.junit.jupiter.api.Test;

class OrthancEventListenerRouteTest extends CamelTestSupport {

    @Override
    protected RoutesBuilder createRouteBuilder() {
        return new OrthancEventListenerRoute();
    }

    @Test
    public void shouldConsumeTheConfiguredEventDestination() throws IOException {
        Properties props = new Properties();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("config/application.properties")) {
            props.load(in);
        }

        assertEquals(OrthancEventListenerRoute.ENDPOINT, props.getProperty("db-event.destinations").trim());
    }

    @Test
    public void shouldAcceptAPatientEventWithoutError() {
        Exchange result = template.send(OrthancEventListenerRoute.ENDPOINT, exchange -> {
            exchange.setProperty("event", null);
            exchange.getMessage().setBody("patient");
        });

        assertNull(result.getException());
    }
}
