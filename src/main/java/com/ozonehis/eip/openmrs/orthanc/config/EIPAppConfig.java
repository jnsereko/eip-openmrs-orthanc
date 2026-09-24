/*
 * Copyright © 2024, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.openmrs.orthanc.config;

import com.ozonehis.eip.openmrs.orthanc.RadiologyConcepts;
import org.openmrs.eip.app.config.AppConfig;
import org.openmrs.eip.fhir.spring.OpenmrsFhirAppConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Import the {@link AppConfig} class to ensure that the required beans are created.
 */
@Configuration
@Import({AppConfig.class, OpenmrsFhirAppConfig.class})
public class EIPAppConfig {

    /**
     * The radiology concept list, from RADIOLOGY_CONCEPT_UUIDS (see {@link RadiologyConcepts}). The
     * empty default means "unset", which RadiologyConcepts turns into its built-in list.
     */
    @Bean
    public RadiologyConcepts radiologyConcepts(@Value("${" + RadiologyConcepts.PROPERTY + ":}") String configured) {
        return new RadiologyConcepts(configured);
    }
}
