package com.hdc.hdc_map_backend.controller.taxBenefits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hdc.hdc_map_backend.entity.User;
import com.hdc.hdc_map_backend.repository.taxBenefits.DealConduitRepository;
import com.hdc.hdc_map_backend.support.BaseIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

class DealConduitControllerIntegrationTest extends BaseIntegrationTest {

    @Autowired private DealConduitRepository conduitRepo;

    private String uniqueUsername() {
        return "u-" + UUID.randomUUID() + "@hdc.local";
    }

    /** Minimal preset payload — enough fields to exercise @JsonUnwrapped round-trip. */
    private String presetPayload(String dealName) {
        return """
                {
                  "projectCost": 50.0,
                  "selectedState": "Washington",
                  "yearOneNOI": 2.0,
                  "investorEquityPct": 25.0,
                  "seniorDebtPct": 20.0,
                  "lihtcEnabled": true,
                  "creditRate": 0.04,
                  "ozEnabled": false,
                  "dealName": "%s",
                  "configurationName": "%s",
                  "isActive": true
                }
                """.formatted(dealName, dealName);
    }

    /** Same shape as presetPayload, but for /configurations endpoints (no isPreset hint needed). */
    private String configPayload(String configName) {
        return """
                {
                  "projectCost": 30.0,
                  "selectedState": "Washington",
                  "yearOneNOI": 1.5,
                  "investorEquityPct": 20.0,
                  "seniorDebtPct": 30.0,
                  "lihtcEnabled": false,
                  "creditRate": 0.04,
                  "ozEnabled": false,
                  "configurationName": "%s",
                  "dealName": "%s"
                }
                """.formatted(configName, configName);
    }

    @Test
    void getPresets_withoutAuth_returns403() throws Exception {
        mockMvc.perform(get("/api/deal-conduits/presets"))
                .andExpect(status().isForbidden());
    }

    @Test
    void createPreset_withAuth_returns201AndRoundTripsFields() throws Exception {
        User user = createUser(uniqueUsername());
        String dealName = "Preset " + UUID.randomUUID();

        mockMvc.perform(post("/api/deal-conduits/presets")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(presetPayload(dealName)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.isPreset").value(true))
                .andExpect(jsonPath("$.dealName").value(dealName))
                .andExpect(jsonPath("$.projectCost").value(50.0))
                .andExpect(jsonPath("$.investorEquityPct").value(25.0))
                .andExpect(jsonPath("$.lihtcEnabled").value(true))
                .andExpect(jsonPath("$.creditRate").value(0.04));
    }

    @Test
    void updatePreset_changesFieldsAndKeepsIsPresetTrue() throws Exception {
        User user = createUser(uniqueUsername());
        String original = "Original " + UUID.randomUUID();

        // Create
        String createResp = mockMvc.perform(post("/api/deal-conduits/presets")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(presetPayload(original)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long id = objectMapper.readTree(createResp).get("id").asLong();

        // Update with a different dealName and projectCost
        String updated = "Updated " + UUID.randomUUID();
        String updateBody = """
                {
                  "projectCost": 75.0,
                  "selectedState": "Washington",
                  "yearOneNOI": 2.0,
                  "investorEquityPct": 25.0,
                  "seniorDebtPct": 20.0,
                  "lihtcEnabled": true,
                  "creditRate": 0.04,
                  "ozEnabled": false,
                  "dealName": "%s",
                  "configurationName": "%s",
                  "isActive": true
                }
                """.formatted(updated, updated);

        mockMvc.perform(put("/api/deal-conduits/presets/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.isPreset").value(true))
                .andExpect(jsonPath("$.dealName").value(updated))
                .andExpect(jsonPath("$.projectCost").value(75.0));
    }

    @Test
    void deletePreset_removesRow() throws Exception {
        User user = createUser(uniqueUsername());
        String dealName = "ToDelete " + UUID.randomUUID();

        String createResp = mockMvc.perform(post("/api/deal-conduits/presets")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(presetPayload(dealName)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long id = objectMapper.readTree(createResp).get("id").asLong();

        mockMvc.perform(delete("/api/deal-conduits/presets/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(user)))
                .andExpect(status().isNoContent());

        assertThat(conduitRepo.findById(id)).isEmpty();
    }

    @Test
    void listConfigurations_onlyReturnsCurrentUsersConfigs() throws Exception {
        User userA = createUser(uniqueUsername());
        User userB = createUser(uniqueUsername());

        // A creates 2 configs
        mockMvc.perform(post("/api/deal-conduits/configurations")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(userA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configPayload("A-1")))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/deal-conduits/configurations")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(userA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configPayload("A-2")))
                .andExpect(status().isCreated());

        // B creates 1 config
        mockMvc.perform(post("/api/deal-conduits/configurations")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(userB))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configPayload("B-1")))
                .andExpect(status().isCreated());

        // A's listing should be exactly A's two configs.
        String response = mockMvc.perform(get("/api/deal-conduits/configurations")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(userA)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Parse and assert all returned names are A's
        com.fasterxml.jackson.databind.JsonNode arr = objectMapper.readTree(response);
        assertThat(arr.isArray()).isTrue();
        for (com.fasterxml.jackson.databind.JsonNode node : arr) {
            String name = node.get("configurationName").asText();
            assertThat(name).startsWith("A-");
        }
        assertThat(arr.size()).isEqualTo(2);
    }

    @Test
    void createConfiguration_assignsOwnerUserIdAndIsPresetFalse() throws Exception {
        User user = createUser(uniqueUsername());

        mockMvc.perform(post("/api/deal-conduits/configurations")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configPayload("MyConfig")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isPreset").value(false))
                .andExpect(jsonPath("$.userId").value(user.getId()));
    }

    @Test
    void getDefaultConfiguration_noneSet_returns404() throws Exception {
        User user = createUser(uniqueUsername());

        mockMvc.perform(get("/api/deal-conduits/configurations/default")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(user)))
                .andExpect(status().isNotFound());
    }

    @Test
    void setAsDefault_unsetsPreviousDefaultForSameUser() throws Exception {
        User user = createUser(uniqueUsername());

        // Create two configs
        String resp1 = mockMvc.perform(post("/api/deal-conduits/configurations")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configPayload("First")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long id1 = objectMapper.readTree(resp1).get("id").asLong();

        String resp2 = mockMvc.perform(post("/api/deal-conduits/configurations")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configPayload("Second")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long id2 = objectMapper.readTree(resp2).get("id").asLong();

        // Set first as default
        mockMvc.perform(put("/api/deal-conduits/configurations/" + id1 + "/set-default")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(user)))
                .andExpect(status().isOk());

        // Set second as default — expect first to lose its default flag
        mockMvc.perform(put("/api/deal-conduits/configurations/" + id2 + "/set-default")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(user)))
                .andExpect(status().isOk());

        // Verify in DB: only id2 has isDefault=true
        var c1 = conduitRepo.findById(id1).orElseThrow();
        var c2 = conduitRepo.findById(id2).orElseThrow();
        assertThat(c1.getPortalSettings().getIsDefault()).as("first config default flag").isNotEqualTo(Boolean.TRUE);
        assertThat(c2.getPortalSettings().getIsDefault()).as("second config default flag").isTrue();
    }
}
