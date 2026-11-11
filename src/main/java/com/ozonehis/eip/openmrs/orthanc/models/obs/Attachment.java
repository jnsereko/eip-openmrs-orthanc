/*
 * Copyright © 2024, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.openmrs.orthanc.models.obs;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Attachment {

    @JsonProperty("uuid")
    public String uuid;

    @JsonProperty("obsDatetime")
    public String obsDatetime;

    @JsonProperty("comment")
    public String comment;

    /**
     * The obs's person, from {@code v=full}. Kept so callers can check the patient themselves
     * rather than trust that OpenMRS applied the {@code patient} search filter.
     */
    @JsonProperty("person")
    public Person person;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Person {

        @JsonProperty("uuid")
        public String uuid;
    }
}
