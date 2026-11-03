/*
 * Copyright © 2024, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.openmrs.orthanc.processors;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Turns an OHIF-authored DICOM SR into the text a clinician reads in the chart.
 *
 * <p>OHIF saves a TID 1500 "Imaging Measurement Report". The radiologist's words are not in
 * SeriesDescription (that is only the name typed in the save dialog): each annotation is a
 * "Measurement Group" container holding the label as a TEXT "Comment" (or a CODE "Finding"),
 * plus NUM items such as "Length 97.15 mm". Free TEXT items outside a group are kept too.
 * Measured on UAT 2026-10-14 (UVL-EMR#427): reading only SeriesDescription dropped all of it.
 *
 * <p>Input is Orthanc's {@code /instances/{id}/simplified-tags} JSON.
 */
public final class SRReportFormatter {

    /** Bookkeeping items OHIF writes into every group; they mean nothing to a clinician. */
    private static final Set<String> IGNORED_CONCEPTS = Set.of(
            "Tracking Identifier", "Tracking Unique Identifier", "Language of Content Item and Descendants",
            "Person Observer Name", "Procedure reported");

    private static final Set<String> LABEL_CONCEPTS = Set.of("Comment", "Finding", "Finding Site");

    private SRReportFormatter() {}

    public static String format(JsonNode tags) {
        String title = text(tags, "SeriesDescription");
        if (title.isEmpty()) {
            title = text(tags, "StudyDescription");
        }
        List<String> lines = new ArrayList<>();
        collect(tags.path("ContentSequence"), lines);

        // One line: the O3 Results tab shows the value in a table cell and drops line breaks
        StringBuilder out = new StringBuilder("Report");
        if (!title.isEmpty()) {
            out.append(": ").append(title);
        }
        if (!lines.isEmpty()) {
            out.append(" \u2014 ").append(String.join("; ", lines)).append('.');
        }
        return out.toString();
    }

    /** "… Reported by: <name>." — unchanged when the author is unknown. */
    public static String withAuthor(String report, String author) {
        if (author == null || author.isBlank()) {
            return report;
        }
        String text = report.endsWith(".") ? report : report + ".";
        return text + " Reported by: " + author.trim() + ".";
    }

    private static void collect(JsonNode items, List<String> lines) {
        for (JsonNode item : items) {
            String type = text(item, "ValueType");
            String concept = conceptName(item);
            if (IGNORED_CONCEPTS.contains(concept)) {
                continue;
            }
            if ("CONTAINER".equals(type) && "Measurement Group".equals(concept)) {
                String line = measurementGroup(item);
                if (!line.isEmpty()) {
                    lines.add(line);
                }
            } else if ("CONTAINER".equals(type)) {
                collect(item.path("ContentSequence"), lines);
            } else if ("TEXT".equals(type)) {
                String value = text(item, "TextValue");
                if (!value.isEmpty()) {
                    lines.add(value);
                }
            }
        }
    }

    /** "label (Length 97.2 mm)", or just the label or just the measurements when one side is missing. */
    private static String measurementGroup(JsonNode group) {
        List<String> labels = new ArrayList<>();
        List<String> measures = new ArrayList<>();
        for (JsonNode item : group.path("ContentSequence")) {
            String type = text(item, "ValueType");
            String concept = conceptName(item);
            if (IGNORED_CONCEPTS.contains(concept)) {
                continue;
            }
            if ("TEXT".equals(type) && LABEL_CONCEPTS.contains(concept)) {
                addIfPresent(labels, text(item, "TextValue"));
            } else if ("CODE".equals(type) && LABEL_CONCEPTS.contains(concept)) {
                addIfPresent(labels, text(item.path("ConceptCodeSequence").path(0), "CodeMeaning"));
            } else if ("TEXT".equals(type)) {
                addIfPresent(labels, text(item, "TextValue"));
            } else if ("NUM".equals(type)) {
                addIfPresent(measures, measurement(concept, item.path("MeasuredValueSequence").path(0)));
            }
        }
        String label = String.join(", ", labels);
        String measure = String.join(", ", measures);
        if (label.isEmpty()) {
            return measure;
        }
        return measure.isEmpty() ? label : label + " (" + measure + ")";
    }

    private static String measurement(String concept, JsonNode measured) {
        String raw = text(measured, "NumericValue");
        if (raw.isEmpty()) {
            return "";
        }
        String value;
        try {
            value = new BigDecimal(raw.trim()).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            value = raw.trim();
        }
        String unit = text(measured.path("MeasurementUnitsCodeSequence").path(0), "CodeValue");
        StringBuilder out = new StringBuilder();
        if (!concept.isEmpty()) {
            out.append(concept).append(' ');
        }
        out.append(value);
        if (!unit.isEmpty() && !"1".equals(unit)) {
            out.append(' ').append(unit);
        }
        return out.toString();
    }

    private static String conceptName(JsonNode item) {
        return text(item.path("ConceptNameCodeSequence").path(0), "CodeMeaning");
    }

    private static void addIfPresent(List<String> list, String value) {
        if (!value.isEmpty()) {
            list.add(value);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isValueNode() ? value.asText("").trim() : "";
    }
}
