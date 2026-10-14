/*
 * Copyright © 2024, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.openmrs.orthanc.processors;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

class SRReportFormatterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldReadFindingAndMeasurementFromOhifReport() throws Exception {
        // Simplified tags of the OHIF SR saved on UAT 2026-10-14 (UVL-EMR#427), observer name redacted
        try (InputStream in = getClass().getResourceAsStream("/sr/ohif-measurement-report.json")) {
            JsonNode tags = mapper.readTree(in);

            assertEquals(
                    "Report: Chest PA\n- UAT test report - no abnormality: Length 97.2 mm",
                    SRReportFormatter.format(tags));
        }
    }

    @Test
    void shouldFallBackToTitleWhenThereIsNoContent() throws Exception {
        JsonNode tags = mapper.readTree("{\"SeriesDescription\":\"Chest PA\"}");

        assertEquals("Report: Chest PA", SRReportFormatter.format(tags));
    }

    @Test
    void shouldKeepMeasurementsWithoutLabelAndFreeText() throws Exception {
        JsonNode tags = mapper.readTree("{\"ContentSequence\":["
                + "{\"ValueType\":\"TEXT\",\"ConceptNameCodeSequence\":[{\"CodeMeaning\":\"Impression\"}],"
                + "\"TextValue\":\"Normal heart size\"},"
                + "{\"ValueType\":\"CONTAINER\",\"ConceptNameCodeSequence\":[{\"CodeMeaning\":\"Measurement Group\"}],"
                + "\"ContentSequence\":[{\"ValueType\":\"NUM\",\"ConceptNameCodeSequence\":[{\"CodeMeaning\":\"Area\"}],"
                + "\"MeasuredValueSequence\":[{\"NumericValue\":\"12.04\","
                + "\"MeasurementUnitsCodeSequence\":[{\"CodeValue\":\"mm2\"}]}]}]}]}");

        assertEquals("Report\n- Normal heart size\n- Area 12 mm2", SRReportFormatter.format(tags));
    }
}
