/*
 * Copyright © 2024, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.openmrs.orthanc.handlers.orthanc;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FHIR values turned into the DICOM values of a worklist entry (UVL-EMR#890). Each returns null when
 * the source is absent or unusable, and the caller then leaves the tag out: the modality fills in
 * its own default for a missing tag, which is better than a made-up value, and an empty value is
 * not what DICOM means by "unknown" for a date.
 */
public final class WorklistFields {

    private static final Pattern FHIR_DATE = Pattern.compile("^(\\d{4})(?:-(\\d{2})(?:-(\\d{2}))?)?(?:T.*)?$");

    private WorklistFields() {}

    /**
     * A FHIR {@code birthDate} as a DICOM DA ({@code yyyyMMdd}). FHIR allows a year or a year and
     * month; DICOM DA needs a whole date, so the first day of the known period is used, which is
     * what OpenMRS stores for an estimated birth date.
     */
    public static String birthDate(String fhirDate) {
        if (fhirDate == null) {
            return null;
        }
        Matcher m = FHIR_DATE.matcher(fhirDate.trim());
        if (!m.matches()) {
            return null;
        }
        try {
            LocalDate date = LocalDate.of(
                    Integer.parseInt(m.group(1)),
                    m.group(2) == null ? 1 : Integer.parseInt(m.group(2)),
                    m.group(3) == null ? 1 : Integer.parseInt(m.group(3)));
            return date.format(DateTimeFormatter.BASIC_ISO_DATE);
        } catch (DateTimeException e) {
            return null;
        }
    }

    /** A FHIR {@code gender} as a DICOM PatientSex: male M, female F, other or unknown O, absent null. */
    public static String sex(String fhirGender) {
        if (fhirGender == null || fhirGender.trim().isEmpty()) {
            return null;
        }
        switch (fhirGender.trim().toLowerCase(Locale.ROOT)) {
            case "male":
                return "M";
            case "female":
                return "F";
            default:
                return "O";
        }
    }

    /**
     * A DICOM PN {@code Family^Given}; the given name is dropped when blank. Null when there is no
     * family name. The PN delimiters {@code ^ = \} inside a component are replaced by a space.
     */
    public static String personName(String family, String given) {
        String f = component(family);
        if (f.isEmpty()) {
            return null;
        }
        String g = component(given);
        return g.isEmpty() ? f : f + "^" + g;
    }

    private static String component(String value) {
        return value == null ? "" : value.replaceAll("[\\^=\\\\]", " ").trim();
    }
}
