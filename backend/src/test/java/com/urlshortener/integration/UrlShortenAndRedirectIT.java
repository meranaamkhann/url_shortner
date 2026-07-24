package com.urlshortener.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UrlShortenAndRedirectIT extends AbstractIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    private String registerAndGetToken(String username) throws Exception {
        String payload = String.format("""
                {"email":"%s@example.com","username":"%s","password":"SecurePass123"}
                """, username, username);
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return json.get("accessToken").asText();
    }

    @Test
    void anonymousUserCanShortenAndRedirect() throws Exception {
        String createPayload = """
                {"longUrl":"https://example.com/some/long/path?query=1"}
                """;

        MvcResult createResult = mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortCode").exists())
                .andExpect(jsonPath("$.longUrl").value("https://example.com/some/long/path?query=1"))
                .andReturn();

        JsonNode json = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String shortCode = json.get("shortCode").asText();

        mockMvc.perform(get("/r/{code}", shortCode))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/some/long/path?query=1"));
    }

    @Test
    void redirectingUnknownCodeReturns404() throws Exception {
        mockMvc.perform(get("/r/doesNotExistXYZ"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void authenticatedUserCanCreateCustomAliasAndSecondAttemptCollides() throws Exception {
        String token = registerAndGetToken("alice_alias_test");

        String createPayload = """
                {"longUrl":"https://example.com/page-one","customAlias":"my-unique-alias-1"}
                """;
        mockMvc.perform(post("/api/v1/urls")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortCode").value("my-unique-alias-1"));

        // Same alias, different destination -> must be rejected as a collision.
        String collidingPayload = """
                {"longUrl":"https://example.com/page-two","customAlias":"my-unique-alias-1"}
                """;
        mockMvc.perform(post("/api/v1/urls")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(collidingPayload))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ALIAS_ALREADY_EXISTS"));
    }

    @Test
    void reservedWordCannotBeUsedAsCustomAlias() throws Exception {
        String token = registerAndGetToken("alice_reserved_test");

        String payload = """
                {"longUrl":"https://example.com/admin-page","customAlias":"admin"}
                """;
        mockMvc.perform(post("/api/v1/urls")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest());
    }

    @Test
    void maliciousUrlIsRejectedAtCreation() throws Exception {
        String payload = """
                {"longUrl":"javascript:alert(document.cookie)"}
                """;
        mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest());
    }

    @Test
    void disablingOwnedUrlMakesRedirectReturn410() throws Exception {
        String token = registerAndGetToken("alice_disable_test");

        String createPayload = """
                {"longUrl":"https://example.com/will-be-disabled"}
                """;
        MvcResult createResult = mockMvc.perform(post("/api/v1/urls")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPayload))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode json = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String id = json.get("id").asText();
        String shortCode = json.get("shortCode").asText();

        mockMvc.perform(post("/api/v1/urls/{id}/disable", id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/r/{code}", shortCode))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.errorCode").value("LINK_DISABLED"));
    }

    @Test
    void cannotDisableAnotherUsersUrl() throws Exception {
        String ownerToken = registerAndGetToken("owner_user");
        String attackerToken = registerAndGetToken("attacker_user");

        String createPayload = """
                {"longUrl":"https://example.com/owned-by-owner"}
                """;
        MvcResult createResult = mockMvc.perform(post("/api/v1/urls")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPayload))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode json = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String id = json.get("id").asText();

        mockMvc.perform(post("/api/v1/urls/{id}/disable", id)
                        .header("Authorization", "Bearer " + attackerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void unauthenticatedRequestToProtectedEndpointReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/urls"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"));
    }
}
