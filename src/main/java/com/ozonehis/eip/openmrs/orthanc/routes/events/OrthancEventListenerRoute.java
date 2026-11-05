/*
 * Copyright © 2024, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.openmrs.orthanc.routes.events;

import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

/**
 * Consumes the database events the watcher sends to {@code db-event.destinations}. The radiology flow polls OpenMRS
 * and Orthanc on its own schedule and needs none of these events, but without a consumer each one fails with "No
 * consumers available" and is kept in the retry queue forever.
 */
@Component
public class OrthancEventListenerRoute extends RouteBuilder {

    public static final String ROUTE_ID = "orthanc-event-listener";

    public static final String ENDPOINT = "direct:orthanc-event-listener";

    @Override
    public void configure() {
        // spotless:off
        from(ENDPOINT)
                .routeId(ROUTE_ID)
                .log(LoggingLevel.DEBUG, "Ignoring ${exchangeProperty.event.operation} event on ${exchangeProperty.event.tableName} id ${exchangeProperty.event.primaryKeyId}")
                .end();
        // spotless:on
    }
}
