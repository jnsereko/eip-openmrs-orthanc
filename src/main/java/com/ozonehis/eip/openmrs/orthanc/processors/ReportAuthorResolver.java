/*
 * Copyright © 2024, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.openmrs.orthanc.processors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ozonehis.eip.openmrs.orthanc.config.OrthancTokenProvider;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Names the person who saved a radiology report (UVL-EMR#429).
 *
 * <p>An OHIF-authored SR carries no author, and the chart result is created by the bridge's
 * service account, so without this nobody can tell who reported. Since OHIF saves under the
 * user's own Keycloak token, the authorization plugin's audit log ({@code EnableAuditLogs})
 * records each upload as {@code uploaded-instance} on the study, with the Keycloak user id and
 * the instance id as its log data. The id is turned into a name through Keycloak's admin API,
 * which the bridge's client may read with {@code realm-management: view-users}.
 *
 * <p>Every failure returns null: a report is still filed without its author rather than not
 * at all.
 */
@Slf4j
@Component
public class ReportAuthorResolver {

    private static final OkHttpClient HTTP = new OkHttpClient();

    @Autowired
    private OrthancTokenProvider orthancTokenProvider;

    @Value("${orthanc.baseUrl:http://orthanc:8042}")
    private String orthancBaseUrl;

    @Value("${OAUTH_ACCESS_TOKEN_URL:}")
    private String tokenUrl;

    public String resolve(String orthancStudyId, String instanceId) {
        try {
            String userId = findUploader(orthancStudyId, instanceId);
            if (userId == null) {
                log.info("No upload audit log for SR {}, filing the report without its author", instanceId);
                return null;
            }
            return userName(userId);
        } catch (Exception e) {
            log.warn("Could not name the author of SR {}: {}", instanceId, e.getMessage());
            return null;
        }
    }

    private String findUploader(String orthancStudyId, String instanceId) throws java.io.IOException {
        JsonNode logs = get(
                orthancBaseUrl + "/auth/audit-logs?resource-id=" + orthancStudyId + "&action=uploaded-instance",
                "token",
                orthancTokenProvider.getToken());
        if (logs == null) {
            return null;
        }
        String quotedId = "\"" + instanceId + "\"";
        for (JsonNode entry : logs) {
            String data = new String(
                    Base64.getDecoder().decode(entry.path("Base64LogData").asText("")), StandardCharsets.UTF_8);
            if (quotedId.equals(data.trim()) || instanceId.equals(data.trim())) {
                String userId = entry.path("UserId").asText("");
                return userId.isEmpty() ? null : userId;
            }
        }
        return null;
    }

    private String userName(String userId) throws java.io.IOException {
        int realms = tokenUrl.indexOf("/realms/");
        int protocol = tokenUrl.indexOf("/protocol/");
        if (realms < 0 || protocol < realms) {
            log.warn("Cannot derive the Keycloak admin URL from OAUTH_ACCESS_TOKEN_URL");
            return null;
        }
        String usersUrl = tokenUrl.substring(0, realms) + "/admin" + tokenUrl.substring(realms, protocol)
                + "/users/" + userId;
        JsonNode user = get(usersUrl, "Authorization", "Bearer " + orthancTokenProvider.getToken());
        if (user == null) {
            return null;
        }
        String name = (user.path("firstName").asText("") + " " + user.path("lastName").asText("")).trim();
        return name.isEmpty() ? user.path("username").asText(null) : name;
    }

    private JsonNode get(String url, String header, String value) throws java.io.IOException {
        Request request = new Request.Builder().url(url).header(header, value).build();
        try (Response response = HTTP.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                log.warn("GET {} answered {}", url.replaceAll("/users/.*", "/users/…"), response.code());
                return null;
            }
            return new ObjectMapper().readTree(response.body().string());
        }
    }
}
