/*
 * Copyright © 2024, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.openmrs.orthanc;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

/**
 * Which order concepts get a modality worklist entry, and which DICOM modality each one is.
 *
 * <p>The list is read from the {@value #PROPERTY} property - set it as the environment variable
 * {@code RADIOLOGY_CONCEPT_UUIDS}, a comma-separated list of concept uuids (UVL-EMR#253). The
 * eip-odoo-openmrs bridge reads the same variable for payment gating, so setting it once for both
 * containers keeps the two on the same list: an order this bridge schedules is one the other bridge
 * can mark paid. With no value it is {@link #DEFAULT_UUIDS}: the RX01-RX07 X-ray and CT concepts,
 * the 22 body-part X-rays added for M3, and the EC01/EC02 ultrasound concepts (UVL-EMR#304,
 * UVL-EMR#340). The eight d0b5d4a0-1xxx X-ray concepts once listed here are gone (UVL-EMR#340): they
 * are in no orderable concept set, and their "Radiology Order" type never becomes a FHIR
 * ServiceRequest.
 *
 * <p>A configured value that is blank or contains anything that is not a uuid is rejected as a
 * whole, with a WARN, and the default is used instead. It never becomes an empty or partial list:
 * an empty list would skip every radiology order at INFO level and no scan would ever reach a
 * modality.
 *
 * <p><b>The shared definition (UVL-EMR#340).</b> When {@value #SET_PROPERTY} names a concept set -
 * environment variable {@code RADIOLOGY_CONCEPT_SET_UUID}, the "Imaging procedures" set - the list
 * is that set's members, read from OpenMRS as FHIR {@code ValueSet/{uuid}} and cached for
 * {@value #CACHE_SECONDS_PROPERTY} seconds (default {@value #DEFAULT_CACHE_SECONDS}). A new imaging
 * concept is then added to the set in OpenMRS and picked up without a jar rebuild. The uuid list
 * above is only the fallback: used until the set has been read once, and whenever it cannot be read
 * or comes back empty. A failed read keeps the last good members and is retried after {@link
 * #RETRY_AFTER_FAILURE}; it never empties the list. Members that are themselves sets are not
 * followed.
 *
 * <p>The modality comes from the concept ({@link #modalityFor}), not from the procedure name. The
 * name heuristic that used to decide it is kept only for a concept this class has no mapping for,
 * such as one added to the concept set later: add its modality to {@link #MODALITY_BY_CONCEPT}.
 *
 * <p>What an X-ray is called on the worklist is configurable ({@value #XRAY_MODALITY_PROPERTY},
 * environment variable {@code ORTHANC_WORKLIST_XRAY_MODALITY}, default {@value
 * #DEFAULT_XRAY_MODALITY}) - see {@link #modalityOf} and UVL-EMR#424.
 */
@Slf4j
public class RadiologyConcepts {

    /** Property the list is read from. Spring maps the environment variable RADIOLOGY_CONCEPT_UUIDS onto it. */
    public static final String PROPERTY = "radiology.concept.uuids";

    /** Concept set whose members are the radiology concepts. Environment variable RADIOLOGY_CONCEPT_SET_UUID. */
    public static final String SET_PROPERTY = "radiology.concept.set.uuid";

    /** How long the set's members are cached. Environment variable RADIOLOGY_CONCEPT_SET_CACHE_SECONDS. */
    public static final String CACHE_SECONDS_PROPERTY = "radiology.concept.set.cache.seconds";

    public static final long DEFAULT_CACHE_SECONDS = 600;

    /** After a failed read of the set, wait this long before asking OpenMRS again. */
    static final Duration RETRY_AFTER_FAILURE = Duration.ofMinutes(1);

    /**
     * Property the worklist modality of an X-ray is read from. Spring maps the environment variable
     * ORTHANC_WORKLIST_XRAY_MODALITY onto it.
     */
    public static final String XRAY_MODALITY_PROPERTY = "orthanc.worklist.xray.modality";

    /**
     * DX (digital radiography), what the UVL X-ray unit - xmaru - writes as the Modality of its
     * own studies. XR is not a DICOM defined term for (0008,0060) at all; a modality that filters
     * its worklist query on its own modality, as DX, would never match an entry labelled XR.
     */
    public static final String DEFAULT_XRAY_MODALITY = "DX";

    /** The generic X-ray modality {@link #MODALITY_BY_CONCEPT} and the name heuristic return. */
    static final String XR = "XR";

    /** A DICOM Code String: at most 16 upper-case letters, digits, underscores or spaces. */
    private static final Pattern CODE_STRING = Pattern.compile("^[A-Z0-9_ ]{1,16}$");

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
        // The 22 body-part X-rays added for M3 (UVL-EMR#212), billed at 20,000 FBu each
        modalities.put("dac7eb20-d3af-4097-a399-2b1a9f3e0ae4", "XR"); // Forearm X-ray
        modalities.put("e6d7bca3-cb2f-4926-bfbc-f89e90d573cd", "XR"); // Humerus X-ray
        modalities.put("97589f4b-8bf9-4342-b24f-605d359ce932", "XR"); // Toe X-ray
        modalities.put("e309ea51-86cd-4a83-883b-d5f8654e6627", "XR"); // Shoulder X-ray
        modalities.put("addae1d8-baa2-459c-b869-e98f0303a688", "XR"); // Ankle X-ray
        modalities.put("245e29a4-82de-4662-b176-92c768e49118", "XR"); // Leg X-ray
        modalities.put("c82d4492-308d-455d-9bf9-360c3a086743", "XR"); // Hand X-ray
        modalities.put("3765eebc-1324-4555-99c8-2b9324d5a185", "XR"); // Pelvis X-ray
        modalities.put("c9f0148b-362a-4b11-9bc3-8c3428a9bbb4", "XR"); // Calcaneus X-ray
        modalities.put("1bd135e8-d15c-45b4-9bc2-a232f5358912", "XR"); // Coccyx X-ray
        modalities.put("1407617f-d79a-43a5-868b-7dedcd2c560f", "XR"); // Neck X-ray
        modalities.put("bb1b2daf-584c-4bb6-8de2-aa678c7231fa", "XR"); // Elbow X-ray
        modalities.put("ba2a2d62-8220-45a7-9302-9092c48a363d", "XR"); // Skull X-ray
        modalities.put("c95e8fa1-b395-4042-9513-d8f33fb81a05", "XR"); // Finger X-ray
        modalities.put("41d7e98f-d485-4d27-81fc-70d44e9d6e1d", "XR"); // Femur X-ray
        modalities.put("1cb9c9f6-d3ad-42db-b38e-9cefa0b258fe", "XR"); // Knee X-ray
        modalities.put("740f0b00-3c24-4c86-b95b-b5c6a7208625", "XR"); // Foot X-ray
        modalities.put("bdd5be47-fc95-4ae3-9319-a8283eeead6a", "XR"); // Wrist X-ray
        modalities.put("e4b471d9-81d6-48c4-8b97-20fdeeeac280", "XR"); // C-Spine X-ray
        modalities.put("30cdefe7-775b-4302-b165-f2540fa207da", "XR"); // L-Spine X-ray
        modalities.put("0c6e5f9a-75cf-444c-9415-6c2ac870392c", "XR"); // T-Spine X-ray
        modalities.put("bc52d033-8df5-466b-abf9-e0679b42c043", "XR"); // Sacrum X-ray
        modalities.put("8155e2e0-5b62-42bc-b47c-0702aaafe3df", "US"); // EC01 - Abdominal ultrasound
        modalities.put("521361cf-ce7d-49a6-9721-8ebad1b76702", "US"); // EC02 - Gastrointestinal ultrasound
        MODALITY_BY_CONCEPT = Collections.unmodifiableMap(modalities);
    }

    public static final Set<String> DEFAULT_UUIDS =
            Collections.unmodifiableSet(new LinkedHashSet<>(MODALITY_BY_CONCEPT.keySet()));

    private final Set<String> uuids;

    private final String xrayModality;

    private final String conceptSetUuid;

    private final Function<String, Set<String>> setMembersLoader;

    private final Duration cacheTtl;

    private final Clock clock;

    private volatile Set<String> setMembers;

    private volatile Instant nextSetRead = Instant.MIN;

    /** The default list, as before this was configurable, and the default X-ray modality. */
    public RadiologyConcepts() {
        this(null);
    }

    /**
     * @param configured the raw {@value #PROPERTY} value; null or blank means "use the default"
     */
    public RadiologyConcepts(String configured) {
        this(configured, null);
    }

    /**
     * @param configured the raw {@value #PROPERTY} value; null or blank means "use the default"
     * @param xrayModality the raw {@value #XRAY_MODALITY_PROPERTY} value; null or blank means
     *     {@value #DEFAULT_XRAY_MODALITY}
     */
    public RadiologyConcepts(String configured, String xrayModality) {
        this(configured, xrayModality, null, null, Duration.ofSeconds(DEFAULT_CACHE_SECONDS), Clock.systemUTC());
    }

    /**
     * @param configured the raw {@value #PROPERTY} value; null or blank means "use the default"
     * @param xrayModality the raw {@value #XRAY_MODALITY_PROPERTY} value; null or blank means
     *     {@value #DEFAULT_XRAY_MODALITY}
     * @param conceptSetUuid the raw {@value #SET_PROPERTY} value; null or blank means "no set"
     * @param setMembersLoader reads the members of a concept set, e.g. {@link FhirConceptSetMembers}
     * @param cacheTtl how long a successful read of the set is used before it is read again
     */
    public RadiologyConcepts(
            String configured,
            String xrayModality,
            String conceptSetUuid,
            Function<String, Set<String>> setMembersLoader,
            Duration cacheTtl,
            Clock clock) {
        this.uuids = resolve(configured);
        this.xrayModality = resolveXrayModality(xrayModality);
        this.conceptSetUuid = resolveSet(conceptSetUuid, setMembersLoader, uuids.size());
        this.setMembersLoader = setMembersLoader;
        this.cacheTtl = cacheTtl;
        this.clock = clock;
    }

    /** The modality an X-ray order is scheduled with. */
    public String getXrayModality() {
        return xrayModality;
    }

    /** The radiology concept uuids: the concept set's members when one is configured and readable. */
    public Set<String> getUuids() {
        if (conceptSetUuid == null) {
            return uuids;
        }
        if (!clock.instant().isBefore(nextSetRead)) {
            readSet();
        }
        Set<String> members = setMembers;
        return members != null ? members : uuids;
    }

    /** Whether an order coded with this concept uuid is a radiology order. */
    public boolean contains(String conceptUuid) {
        return conceptUuid != null && getUuids().contains(conceptUuid);
    }

    private synchronized void readSet() {
        Instant now = clock.instant();
        if (now.isBefore(nextSetRead)) {
            return; // another thread read it while this one waited
        }
        try {
            Set<String> loaded = setMembersLoader.apply(conceptSetUuid);
            if (loaded == null || loaded.isEmpty()) {
                throw new IllegalStateException("it has no members");
            }
            if (!loaded.equals(setMembers)) {
                log.info(
                        "{} {} - using its {} members as the radiology concepts: {}",
                        SET_PROPERTY,
                        conceptSetUuid,
                        loaded.size(),
                        loaded);
                for (String member : loaded) {
                    if (!MODALITY_BY_CONCEPT.containsKey(member)) {
                        log.info("Concept set member {} has no mapped modality - it is guessed from the order name", member);
                    }
                }
            }
            setMembers = Collections.unmodifiableSet(new LinkedHashSet<>(loaded));
            nextSetRead = now.plus(cacheTtl);
        } catch (RuntimeException e) {
            nextSetRead = now.plus(RETRY_AFTER_FAILURE);
            Set<String> members = setMembers;
            if (members == null) {
                log.warn(
                        "Could not read the radiology concept set {} ({}) - using the {} {} radiology concepts until it can be read",
                        conceptSetUuid,
                        e.getMessage(),
                        uuids.size(),
                        uuids == DEFAULT_UUIDS ? "default" : "configured");
            } else {
                log.warn(
                        "Could not re-read the radiology concept set {} ({}) - keeping its {} members read before",
                        conceptSetUuid,
                        e.getMessage(),
                        members.size());
            }
        }
    }

    private static String resolveSet(String conceptSetUuid, Function<String, Set<String>> loader, int fallbackSize) {
        if (conceptSetUuid == null || conceptSetUuid.trim().isEmpty()) {
            return null;
        }
        String uuid = conceptSetUuid.trim();
        if (!UUID.matcher(uuid).matches() || loader == null) {
            log.warn(
                    "{} is invalid ('{}') - IGNORING it and using the list of {} radiology concepts",
                    SET_PROPERTY,
                    conceptSetUuid,
                    fallbackSize);
            return null;
        }
        log.info("{} set - the radiology concepts are the members of concept set {}", SET_PROPERTY, uuid);
        return uuid;
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

    /**
     * The modality to put on the worklist entry: {@link #modalityFor}, with an X-ray written as the
     * configured {@link #getXrayModality() X-ray modality} instead of XR (UVL-EMR#424).
     *
     * <p>The worklist is read by the X-ray unit, and an entry has to carry the modality that unit
     * calls itself. xmaru encodes its X-rays as DX, so an entry labelled XR is skipped by any
     * worklist query it filters on its own modality - the order is on the worklist and still never
     * reaches the machine. US and CT are left as they are: they are already the defined terms
     * those modalities use.
     *
     * <p>Every route to XR is replaced, the mapped X-ray concepts and the name heuristic's
     * fallback alike, so a configured concept with no mapping is scheduled the same way.
     */
    public String modalityOf(String conceptUuid, String procedureName) {
        String modality = modalityFor(conceptUuid, procedureName);
        return XR.equals(modality) ? xrayModality : modality;
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

    /**
     * The configured X-ray modality, upper-cased, or the default if it is unset or not a DICOM Code
     * String. A bad value is rejected with a WARN rather than written to the worklist: Orthanc would
     * accept it, and the entry would then match no modality's query, in silence.
     */
    private static String resolveXrayModality(String configured) {
        if (configured == null || configured.trim().isEmpty()) {
            log.info("{} not set - X-rays are scheduled as {}", XRAY_MODALITY_PROPERTY, DEFAULT_XRAY_MODALITY);
            return DEFAULT_XRAY_MODALITY;
        }
        String modality = configured.trim().toUpperCase(Locale.ROOT);
        if (!CODE_STRING.matcher(modality).matches()) {
            log.warn("{} is not a DICOM modality code ('{}') - IGNORING it, X-rays are scheduled as {}",
                    XRAY_MODALITY_PROPERTY, configured, DEFAULT_XRAY_MODALITY);
            return DEFAULT_XRAY_MODALITY;
        }
        log.info("{} set - X-rays are scheduled as {}", XRAY_MODALITY_PROPERTY, modality);
        return modality;
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
