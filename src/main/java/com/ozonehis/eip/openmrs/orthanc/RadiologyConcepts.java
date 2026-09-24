/*
 * Copyright © 2024, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.openmrs.orthanc;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

/**
 * Which order concepts get a modality worklist entry, and which DICOM modality each one is.
 *
 * <p>The list is read from the {@value #PROPERTY} property - set it as the environment variable
 * {@code RADIOLOGY_CONCEPT_UUIDS}, a comma-separated list of concept uuids (UVL-EMR#253). The
 * eip-odoo-openmrs bridge reads the same variable for payment gating, so setting it once for both
 * containers keeps the two on the same list: an order this bridge schedules is one the other bridge
 * can mark paid. With no value it is {@link #DEFAULT_UUIDS}: the X-ray and CT concepts plus the
 * EC01/EC02 ultrasound concepts (UVL-EMR#304). eip-odoo-openmrs carries the same default.
 *
 * <p>A configured value that is blank or contains anything that is not a uuid is rejected as a
 * whole, with a WARN, and the default is used instead. It never becomes an empty or partial list:
 * an empty list would skip every radiology order at INFO level and no scan would ever reach a
 * modality.
 *
 * <p>The modality comes from the concept ({@link #modalityFor}), not from the procedure name. The
 * name heuristic that used to decide it is kept only for a configured concept this class has no
 * mapping for.
 */
@Slf4j
public class RadiologyConcepts {

    /** Property the list is read from. Spring maps the environment variable RADIOLOGY_CONCEPT_UUIDS onto it. */
    public static final String PROPERTY = "radiology.concept.uuids";

    private static final Pattern UUID =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");

    /** DICOM modality (0008,0060) of each known radiology concept. Its keys are the default list. */
    public static final Map<String, String> MODALITY_BY_CONCEPT;

    static {
        Map<String, String> modalities = new LinkedHashMap<>();
        modalities.put("e3dea2c8-62c6-4487-bdaa-1d009642f7ad", "XR"); // RX01 - Chest X-ray
        modalities.put("82e7d36c-078d-40c6-9854-92b376099307", "XR"); // RX02 - Abdominal X-ray
        modalities.put("701257a2-885e-4249-8319-d9597d2970af", "XR"); // RX03 - Bone X-ray
        modalities.put("b25dcc00-800f-48ac-b31a-f1e9cc53d787", "XR"); // RX04 - Intravenous urography
        modalities.put("81e0643c-a871-475e-8bd5-93945da8877d", "XR"); // RX05 - Salpingo-urethrogram
        modalities.put("1a5e3d73-f897-47ed-840b-d4537b7cc586", "XR"); // RX06 - Barium enema
        modalities.put("0a5ba175-fb7e-4d66-aa6a-ba058f3468c1", "CT"); // RX07 - CT scan
        modalities.put("d0b5d4a0-1001-0000-0000-000000000001", "XR"); // Chest X-Ray
        modalities.put("d0b5d4a0-1002-0000-0000-000000000001", "XR"); // Abdominal X-Ray
        modalities.put("d0b5d4a0-1003-0000-0000-000000000001", "XR"); // Hand X-Ray
        modalities.put("d0b5d4a0-1004-0000-0000-000000000001", "XR"); // Foot X-Ray
        modalities.put("d0b5d4a0-1005-0000-0000-000000000001", "XR"); // Knee X-Ray
        modalities.put("d0b5d4a0-1006-0000-0000-000000000001", "XR"); // Spine X-Ray
        modalities.put("d0b5d4a0-1007-0000-0000-000000000001", "XR"); // Pelvis X-Ray
        modalities.put("d0b5d4a0-1008-0000-0000-000000000001", "XR"); // Skull X-Ray
        modalities.put("8155e2e0-5b62-42bc-b47c-0702aaafe3df", "US"); // EC01 - Abdominal ultrasound
        modalities.put("521361cf-ce7d-49a6-9721-8ebad1b76702", "US"); // EC02 - Gastrointestinal ultrasound
        MODALITY_BY_CONCEPT = Collections.unmodifiableMap(modalities);
    }

    public static final Set<String> DEFAULT_UUIDS =
            Collections.unmodifiableSet(new LinkedHashSet<>(MODALITY_BY_CONCEPT.keySet()));

    private final Set<String> uuids;

    /** The default list, as before this was configurable. */
    public RadiologyConcepts() {
        this(null);
    }

    /**
     * @param configured the raw {@value #PROPERTY} value; null or blank means "use the default"
     */
    public RadiologyConcepts(String configured) {
        this.uuids = resolve(configured);
    }

    public Set<String> getUuids() {
        return uuids;
    }

    /** Whether an order coded with this concept uuid is a radiology order. */
    public boolean contains(String conceptUuid) {
        return conceptUuid != null && uuids.contains(conceptUuid);
    }

    /**
     * The DICOM modality for an order coded with this concept.
     *
     * <p>A known concept maps to its modality regardless of the procedure name. Only a concept with
     * no mapping - one added through {@value #PROPERTY} - falls back to guessing from the name, and
     * that guess ignores case and accents, so "Échographie abdominale" is US. XR when nothing
     * matches.
     */
    public static String modalityFor(String conceptUuid, String procedureName) {
        String mapped = conceptUuid == null ? null : MODALITY_BY_CONCEPT.get(conceptUuid);
        if (mapped != null) {
            return mapped;
        }
        String modality = modalityFromName(procedureName);
        log.debug("No modality mapped for concept {} - '{}' guessed from the name", conceptUuid, modality);
        return modality;
    }

    /** The name heuristic that decided every modality before UVL-EMR#304, made accent-insensitive. */
    static String modalityFromName(String procedureName) {
        if (procedureName == null) {
            return "XR";
        }
        String desc = DIACRITICS
                .matcher(Normalizer.normalize(procedureName, Normalizer.Form.NFD))
                .replaceAll("")
                .toLowerCase(Locale.ROOT);
        if (desc.contains("ct") || desc.contains("scan")) return "CT";
        if (desc.contains("ultrasound") || desc.contains("echo")) return "US";
        if (desc.contains("mri") || desc.contains("mr ")) return "MR";
        return "XR";
    }

    private static Set<String> resolve(String configured) {
        if (configured == null || configured.trim().isEmpty()) {
            log.info("{} not set - using the default {} radiology concepts", PROPERTY, DEFAULT_UUIDS.size());
            return DEFAULT_UUIDS;
        }
        Set<String> parsed = new LinkedHashSet<>();
        List<String> invalid = new ArrayList<>();
        for (String entry : configured.split(",")) {
            String uuid = entry.trim();
            if (uuid.isEmpty()) {
                continue;
            }
            if (UUID.matcher(uuid).matches()) {
                parsed.add(uuid);
            } else {
                invalid.add(uuid);
            }
        }
        if (!invalid.isEmpty() || parsed.isEmpty()) {
            log.warn(
                    "{} is invalid ({}) - IGNORING it and using the default {} radiology concepts. Value was '{}'",
                    PROPERTY,
                    invalid.isEmpty() ? "no uuids in it" : "not uuids: " + invalid,
                    DEFAULT_UUIDS.size(),
                    configured);
            return DEFAULT_UUIDS;
        }
        log.info("{} set - using {} configured radiology concepts: {}", PROPERTY, parsed.size(), parsed);
        return Collections.unmodifiableSet(parsed);
    }
}
