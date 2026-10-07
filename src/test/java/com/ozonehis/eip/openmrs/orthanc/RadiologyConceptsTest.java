/*
 * Copyright © 2024, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.openmrs.orthanc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * The radiology concept list is configurable and never silently empty (UVL-EMR#253), the modality
 * comes from the concept (UVL-EMR#304), and the list is read from a shared concept set when one is
 * configured (UVL-EMR#340).
 */
class RadiologyConceptsTest {

    private static final String RX01 = "e3dea2c8-62c6-4487-bdaa-1d009642f7ad";

    private static final String RX02 = "82e7d36c-078d-40c6-9854-92b376099307";

    private static final String RX07 = "0a5ba175-fb7e-4d66-aa6a-ba058f3468c1";

    private static final String EC01 = "8155e2e0-5b62-42bc-b47c-0702aaafe3df";

    private static final String EC02 = "521361cf-ce7d-49a6-9721-8ebad1b76702";

    private static final String OTHER = "11111111-2222-3333-4444-555555555555";

    private static final String SET = "6a1c3f0e-5d2b-4c8e-9f47-1b2e3d4c5a60";

    /** RX01-RX07: the orderable X-ray and CT concepts both bridges hard-coded before #253. */
    private static final Set<String> RX01_TO_RX07 = new LinkedHashSet<>(Arrays.asList(
            "e3dea2c8-62c6-4487-bdaa-1d009642f7ad",
            "82e7d36c-078d-40c6-9854-92b376099307",
            "701257a2-885e-4249-8319-d9597d2970af",
            "b25dcc00-800f-48ac-b31a-f1e9cc53d787",
            "81e0643c-a871-475e-8bd5-93945da8877d",
            "1a5e3d73-f897-47ed-840b-d4537b7cc586",
            "0a5ba175-fb7e-4d66-aa6a-ba058f3468c1"));

    private static final String ANKLE = "addae1d8-baa2-459c-b869-e98f0303a688";

    /** The 22 body-part X-rays added for M3, each with its own Odoo product (UVL-EMR#212). */
    private static final Set<String> M3_XRAYS = new LinkedHashSet<>(Arrays.asList(
            "dac7eb20-d3af-4097-a399-2b1a9f3e0ae4", "e6d7bca3-cb2f-4926-bfbc-f89e90d573cd",
            "97589f4b-8bf9-4342-b24f-605d359ce932", "e309ea51-86cd-4a83-883b-d5f8654e6627",
            ANKLE, "245e29a4-82de-4662-b176-92c768e49118",
            "c82d4492-308d-455d-9bf9-360c3a086743", "3765eebc-1324-4555-99c8-2b9324d5a185",
            "c9f0148b-362a-4b11-9bc3-8c3428a9bbb4", "1bd135e8-d15c-45b4-9bc2-a232f5358912",
            "1407617f-d79a-43a5-868b-7dedcd2c560f", "bb1b2daf-584c-4bb6-8de2-aa678c7231fa",
            "ba2a2d62-8220-45a7-9302-9092c48a363d", "c95e8fa1-b395-4042-9513-d8f33fb81a05",
            "41d7e98f-d485-4d27-81fc-70d44e9d6e1d", "1cb9c9f6-d3ad-42db-b38e-9cefa0b258fe",
            "740f0b00-3c24-4c86-b95b-b5c6a7208625", "bdd5be47-fc95-4ae3-9319-a8283eeead6a",
            "e4b471d9-81d6-48c4-8b97-20fdeeeac280", "30cdefe7-775b-4302-b165-f2540fa207da",
            "0c6e5f9a-75cf-444c-9415-6c2ac870392c", "bc52d033-8df5-466b-abf9-e0679b42c043"));

    /**
     * The default since #340: RX01-RX07, the 22 M3 X-rays and the EC01/EC02 ultrasounds - the members
     * of the "Imaging procedures" set - without the dead d0b5d4a0 X-rays.
     */
    private static final Set<String> DEFAULT_SINCE_304 = new LinkedHashSet<>(RX01_TO_RX07);

    static {
        DEFAULT_SINCE_304.addAll(M3_XRAYS);
        DEFAULT_SINCE_304.add(EC01);
        DEFAULT_SINCE_304.add(EC02);
    }

    @Test
    void theM3BodyPartXraysAreRadiologyAndScheduledAsDx() {
        RadiologyConcepts concepts = new RadiologyConcepts();
        for (String uuid : M3_XRAYS) {
            assertTrue(concepts.contains(uuid), uuid);
            assertEquals("XR", RadiologyConcepts.modalityFor(uuid, "Radiographie"), uuid);
            assertEquals("DX", concepts.modalityOf(uuid, null), uuid);
        }
        // The ankle X-ray that production skipped on 2026-10-27 ("Skipping non-radiology SR").
        assertTrue(concepts.contains(ANKLE));
    }

    @Test
    void defaultIsRx01ToRx07TheM3XraysAndTheTwoUltrasounds() {
        assertEquals(31, RadiologyConcepts.DEFAULT_UUIDS.size());
        assertEquals(DEFAULT_SINCE_304, RadiologyConcepts.DEFAULT_UUIDS);
        assertEquals(DEFAULT_SINCE_304, new RadiologyConcepts().getUuids());
        assertEquals(DEFAULT_SINCE_304, new RadiologyConcepts(null).getUuids());
    }

    @Test
    void ultrasoundConceptsAreRadiologyByDefault() {
        RadiologyConcepts concepts = new RadiologyConcepts();

        assertTrue(concepts.contains(EC01));
        assertTrue(concepts.contains(EC02));
    }

    @Test
    void everyDefaultConceptHasAModality() {
        assertEquals(RadiologyConcepts.DEFAULT_UUIDS, RadiologyConcepts.MODALITY_BY_CONCEPT.keySet());
    }

    @Test
    void modalityComesFromTheConceptNotTheName() {
        // Names chosen so the old heuristic would have got each one wrong.
        assertEquals("US", RadiologyConcepts.modalityFor(EC01, "EC01 - Abdominal"));
        assertEquals("US", RadiologyConcepts.modalityFor(EC02, "Gastrointestinal"));
        assertEquals("CT", RadiologyConcepts.modalityFor(RX07, "RX07 - Head"));
        assertEquals("XR", RadiologyConcepts.modalityFor(RX01, "RX01 - Chest X-ray"));
        assertEquals("XR", RadiologyConcepts.modalityFor(RX01, "Chest scan"));
        assertEquals("CT", RadiologyConcepts.modalityFor(RX07, "Barium enema"));
    }

    @Test
    void anUnmappedConfiguredConceptFallsBackToTheNameHeuristic() {
        assertEquals("CT", RadiologyConcepts.modalityFor(OTHER, "CT Scan Head"));
        assertEquals("US", RadiologyConcepts.modalityFor(OTHER, "Pelvic ultrasound"));
        assertEquals("MR", RadiologyConcepts.modalityFor(OTHER, "MRI brain"));
        assertEquals("XR", RadiologyConcepts.modalityFor(OTHER, "Chest X-ray"));
        assertEquals("XR", RadiologyConcepts.modalityFor(OTHER, null));
        assertEquals("XR", RadiologyConcepts.modalityFor(null, null));
    }

    @Test
    void theNameHeuristicIgnoresAccents() {
        assertEquals("US", RadiologyConcepts.modalityFor(OTHER, "Échographie abdominale"));
        assertEquals("US", RadiologyConcepts.modalityFor(OTHER, "ÉCHOGRAPHIE PELVIENNE"));
        assertEquals("US", RadiologyConcepts.modalityFor(OTHER, "Echocardiographie"));
    }

    @Test
    void configuredListReplacesTheDefault() {
        RadiologyConcepts concepts = new RadiologyConcepts(" " + RX02 + " , " + OTHER + ",");

        assertEquals(new LinkedHashSet<>(Arrays.asList(RX02, OTHER)), concepts.getUuids());
        assertTrue(concepts.contains(OTHER));
        assertTrue(concepts.contains(RX02));
        // Replaces, not adds to: RX01 is in the default but not in this list.
        assertFalse(concepts.contains(RX01));
    }

    @Test
    void blankConfigurationFallsBackToTheDefault() {
        for (String blank : new String[] {"", "   ", ",", " , ,"}) {
            RadiologyConcepts concepts = new RadiologyConcepts(blank);
            assertEquals(DEFAULT_SINCE_304, concepts.getUuids(), "for '" + blank + "'");
            assertTrue(concepts.contains(RX01));
        }
    }

    @Test
    void configurationWithAnythingThatIsNotAUuidFallsBackToTheWholeDefault() {
        // One typo must not leave a partial list: that would drop the mistyped concept in silence.
        RadiologyConcepts concepts = new RadiologyConcepts(RX02 + ",RX01," + OTHER);

        assertEquals(DEFAULT_SINCE_304, concepts.getUuids());
        assertTrue(concepts.contains(RX01));
        assertFalse(concepts.contains(OTHER));
    }

    @Test
    void theListIsNeverEmpty() {
        for (String value : new String[] {null, "", ",", "not-a-uuid", RX01}) {
            assertFalse(new RadiologyConcepts(value).getUuids().isEmpty(), "for '" + value + "'");
        }
    }

    @Test
    void nonRadiologyOrdersAreNotMatched() {
        RadiologyConcepts concepts = new RadiologyConcepts();

        assertFalse(concepts.contains(null));
        assertFalse(concepts.contains(""));
        assertFalse(concepts.contains(OTHER));
        assertTrue(concepts.contains(RX01));
    }

    @Test
    void theEnvironmentVariableRadiologyConceptUuidsResolvesTheProperty() {
        // How the value reaches the container: compose sets RADIOLOGY_CONCEPT_UUIDS, and Spring's
        // environment lookup maps it onto radiology.concept.uuids, which EIPAppConfig reads. eip-odoo-openmrs reads the same variable.
        StandardEnvironment environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .replace(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new SystemEnvironmentPropertySource(
                                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                Collections.<String, Object>singletonMap("RADIOLOGY_CONCEPT_UUIDS", RX02)));

        String resolved = environment.resolvePlaceholders("${" + RadiologyConcepts.PROPERTY + ":}");

        assertEquals(RX02, resolved);
        assertEquals(Collections.singleton(RX02), new RadiologyConcepts(resolved).getUuids());
    }

    @Test
    void anXrayIsScheduledAsDxByDefault() {
        // UVL-EMR#424: xmaru encodes its X-rays as DX; an entry labelled XR is skipped by its query.
        RadiologyConcepts concepts = new RadiologyConcepts();

        assertEquals("DX", concepts.getXrayModality());
        assertEquals("DX", concepts.modalityOf(RX01, "RX01 - Chest X-ray"));
        assertEquals("DX", concepts.modalityOf("d0b5d4a0-1006-0000-0000-000000000001", "Spine"));
        // The name heuristic's X-ray fallback goes the same way.
        assertEquals("DX", concepts.modalityOf(OTHER, "Chest X-ray"));
        assertEquals("DX", concepts.modalityOf(null, null));
    }

    @Test
    void ultrasoundAndCtAreNotAffectedByTheXrayModality() {
        RadiologyConcepts concepts = new RadiologyConcepts(null, "CR");

        assertEquals("US", concepts.modalityOf(EC01, "EC01 - Abdominal"));
        assertEquals("US", concepts.modalityOf(EC02, "Gastrointestinal"));
        assertEquals("CT", concepts.modalityOf(RX07, "RX07 - Head"));
        assertEquals("MR", concepts.modalityOf(OTHER, "MRI brain"));
        assertEquals("CR", concepts.modalityOf(RX01, "RX01 - Chest X-ray"));
    }

    @Test
    void theXrayModalityIsConfigurableAndUpperCased() {
        assertEquals("CR", new RadiologyConcepts(null, " cr ").getXrayModality());
        assertEquals("XR", new RadiologyConcepts(null, "XR").getXrayModality());
    }

    @Test
    void aBlankOrInvalidXrayModalityFallsBackToDx() {
        for (String value : new String[] {null, "", "   ", "D-X", "DIGITAL RADIOGRAPHY X", "DX;CR"}) {
            assertEquals("DX", new RadiologyConcepts(null, value).getXrayModality(), "for '" + value + "'");
        }
    }

    @Test
    void deadD0b5d4a0XraysAreNotInTheDefault() {
        // They are in no orderable concept set and their order type never becomes a ServiceRequest.
        for (String uuid : RadiologyConcepts.DEFAULT_UUIDS) {
            assertFalse(uuid.startsWith("d0b5d4a0-"), uuid);
        }
    }

    // ---- The shared concept set (UVL-EMR#340) ----

    /** A clock the test moves by hand. */
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-10-07T08:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** A set loader that returns, or throws, whatever the test queued, and counts its calls. */
    private static final class FakeSet implements Function<String, Set<String>> {
        private final List<Object> answers = new ArrayList<>();
        private final List<String> calls = new ArrayList<>();

        FakeSet then(Object answer) {
            answers.add(answer);
            return this;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Set<String> apply(String setUuid) {
            calls.add(setUuid);
            Object answer = answers.size() > 1 ? answers.remove(0) : answers.get(0);
            if (answer instanceof RuntimeException) {
                throw (RuntimeException) answer;
            }
            return (Set<String>) answer;
        }
    }

    private static Set<String> setOf(String... uuids) {
        return new LinkedHashSet<>(Arrays.asList(uuids));
    }

    private final TestClock clock = new TestClock();

    private RadiologyConcepts withSet(FakeSet set) {
        return new RadiologyConcepts(null, null, SET, set, Duration.ofMinutes(10), clock);
    }

    @Test
    void theConceptSetMembersReplaceTheList() {
        FakeSet set = new FakeSet().then(setOf(RX02, OTHER));
        RadiologyConcepts concepts = withSet(set);

        assertEquals(setOf(RX02, OTHER), concepts.getUuids());
        // A concept added to the set gets a worklist entry without a rebuild; one left out does not.
        assertTrue(concepts.contains(OTHER));
        assertFalse(concepts.contains(RX01));
        assertEquals(Collections.singletonList(SET), set.calls);
    }

    @Test
    void aSetMemberWithoutAMappedModalityIsGuessedFromItsName() {
        RadiologyConcepts concepts = withSet(new FakeSet().then(setOf(OTHER)));

        assertTrue(concepts.contains(OTHER));
        assertEquals("US", RadiologyConcepts.modalityFor(OTHER, "Echographie pelvienne"));
    }

    @Test
    void theSetIsCachedAndReadAgainWhenTheCacheExpires() {
        FakeSet set = new FakeSet().then(setOf(RX01)).then(setOf(RX01, OTHER));
        RadiologyConcepts concepts = withSet(set);

        assertEquals(setOf(RX01), concepts.getUuids());
        clock.advance(Duration.ofMinutes(9));
        concepts.contains(RX01);
        assertEquals(setOf(RX01), concepts.getUuids());
        assertEquals(1, set.calls.size(), "read once within the cache period");

        clock.advance(Duration.ofMinutes(2));
        assertEquals(setOf(RX01, OTHER), concepts.getUuids());
        assertEquals(2, set.calls.size());
    }

    @Test
    void anUnreadableSetFallsBackToTheListAndIsRetriedAfterAMinute() {
        FakeSet set = new FakeSet().then(new RuntimeException("HTTP 401")).then(setOf(OTHER));
        RadiologyConcepts concepts = withSet(set);

        assertEquals(DEFAULT_SINCE_304, concepts.getUuids());
        concepts.getUuids();
        assertEquals(1, set.calls.size(), "not retried on every order");

        clock.advance(RadiologyConcepts.RETRY_AFTER_FAILURE);
        assertEquals(setOf(OTHER), concepts.getUuids());
    }

    @Test
    void anUnreadableSetFallsBackToTheConfiguredListWhenThereIsOne() {
        FakeSet set = new FakeSet().then(new RuntimeException("connection refused"));
        RadiologyConcepts concepts = new RadiologyConcepts(RX02, null, SET, set, Duration.ofMinutes(10), clock);

        assertEquals(Collections.singleton(RX02), concepts.getUuids());
    }

    @Test
    void aFailedReReadKeepsTheMembersReadBefore() {
        FakeSet set = new FakeSet().then(setOf(OTHER)).then(new RuntimeException("timeout"));
        RadiologyConcepts concepts = withSet(set);

        assertEquals(setOf(OTHER), concepts.getUuids());
        clock.advance(Duration.ofMinutes(11));
        assertEquals(setOf(OTHER), concepts.getUuids());
        assertEquals(2, set.calls.size());
    }

    @Test
    void anEmptySetNeverEmptiesTheList() {
        RadiologyConcepts concepts = withSet(new FakeSet().then(Collections.<String>emptySet()));

        assertEquals(DEFAULT_SINCE_304, concepts.getUuids());
        assertTrue(concepts.contains(RX01));
    }

    @Test
    void aSetUuidThatIsNotAUuidIsIgnored() {
        FakeSet set = new FakeSet().then(setOf(OTHER));
        RadiologyConcepts concepts = new RadiologyConcepts(null, null, "Imaging procedures", set, Duration.ofMinutes(10), clock);

        assertEquals(DEFAULT_SINCE_304, concepts.getUuids());
        assertTrue(set.calls.isEmpty());
    }

    @Test
    void theEnvironmentVariableOrthancWorklistXrayModalityResolvesTheProperty() {
        StandardEnvironment environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .replace(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new SystemEnvironmentPropertySource(
                                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                Collections.<String, Object>singletonMap("ORTHANC_WORKLIST_XRAY_MODALITY", "CR")));

        String resolved = environment.resolvePlaceholders("${" + RadiologyConcepts.XRAY_MODALITY_PROPERTY + ":}");

        assertEquals("CR", resolved);
        assertEquals("CR", new RadiologyConcepts(null, resolved).getXrayModality());
    }

    @Test
    void theEnvironmentVariableRadiologyConceptSetUuidResolvesTheSetProperty() {
        StandardEnvironment environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .replace(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new SystemEnvironmentPropertySource(
                                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                Collections.<String, Object>singletonMap("RADIOLOGY_CONCEPT_SET_UUID", SET)));

        assertEquals(SET, environment.resolvePlaceholders("${" + RadiologyConcepts.SET_PROPERTY + ":}"));
    }
}
