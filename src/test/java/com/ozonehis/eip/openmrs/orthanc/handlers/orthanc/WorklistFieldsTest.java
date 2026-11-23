/*
 * Copyright © 2024, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.openmrs.orthanc.handlers.orthanc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** FHIR to DICOM value mapping of the worklist entry (UVL-EMR#890). */
class WorklistFieldsTest {

    @Test
    void birthDateIsADicomDateFromAFullPartialOrAbsentFhirDate() {
        assertEquals("19840307", WorklistFields.birthDate("1984-03-07"));
        assertEquals("19840307", WorklistFields.birthDate("1984-03-07T00:00:00.000+00:00"));
        assertEquals("19840301", WorklistFields.birthDate("1984-03"));
        assertEquals("19660101", WorklistFields.birthDate("1966"));
        assertNull(WorklistFields.birthDate(null));
        assertNull(WorklistFields.birthDate(""));
        assertNull(WorklistFields.birthDate("unknown"));
        assertNull(WorklistFields.birthDate("1984-13-45"));
    }

    @Test
    void sexMapsMaleFemaleAndEverythingElseToO() {
        assertEquals("M", WorklistFields.sex("male"));
        assertEquals("F", WorklistFields.sex("female"));
        assertEquals("F", WorklistFields.sex("FEMALE"));
        assertEquals("O", WorklistFields.sex("other"));
        assertEquals("O", WorklistFields.sex("unknown"));
        assertNull(WorklistFields.sex(null));
        assertNull(WorklistFields.sex(" "));
    }

    @Test
    void personNameIsFamilyCaretGivenAndNullWithoutFamily() {
        assertEquals("Doe^Jane", WorklistFields.personName("Doe", "Jane"));
        assertEquals("Doe", WorklistFields.personName("Doe", ""));
        assertEquals("Doe", WorklistFields.personName("Doe", null));
        assertEquals("Dr Roe", WorklistFields.personName("Dr ^Roe", null).replace("  ", " "));
        assertNull(WorklistFields.personName("", "Jane"));
        assertNull(WorklistFields.personName(null, null));
    }
}
