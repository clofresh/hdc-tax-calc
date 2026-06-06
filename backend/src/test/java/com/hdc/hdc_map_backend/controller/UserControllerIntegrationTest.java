package com.hdc.hdc_map_backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.hdc.hdc_map_backend.entity.PasswordResetToken;
import com.hdc.hdc_map_backend.entity.User;
import com.hdc.hdc_map_backend.repository.user.PasswordResetTokenRepository;
import com.hdc.hdc_map_backend.service.EmailService;
import com.hdc.hdc_map_backend.service.GoogleOAuthService;
import com.hdc.hdc_map_backend.support.BaseIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class UserControllerIntegrationTest extends BaseIntegrationTest {

    private String uniqueUsername() {
        return "u-" + UUID.randomUUID() + "@hdc.local";
    }

    @MockitoBean private EmailService emailService;
    @MockitoBean private GoogleOAuthService googleOAuthService;
    @Autowired private PasswordResetTokenRepository tokenRepo;

    @Test
    void register_happyPath_persistsUserWithBcryptPassword() throws Exception {
        String username = uniqueUsername();
        String body = """
                {"username":"%s","password":"testpass","fullName":"Test User"}
                """.formatted(username);

        mockMvc.perform(post("/api/public/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.role").value("USER"));

        User saved = userRepo.findByUsername(username).orElseThrow();
        assertThat(saved.getPassword()).startsWith("$2a$");
        assertThat(saved.getPassword()).isNotEqualTo("testpass");
        assertThat(saved.getFullName()).isEqualTo("Test User");
    }

    @Test
    void register_existingUsername_currentlyCreatesDuplicateRow_KNOWN_BUG() throws Exception {
        // TODO: when the duplicate-username-fix thread lands, this test should
        // be updated to assert 409 Conflict and a single row.
        String username = uniqueUsername();
        String body = """
                {"username":"%s","password":"testpass","fullName":"Test"}
                """.formatted(username);

        mockMvc.perform(post("/api/public/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // Second registration with same username — currently succeeds, creating a dup.
        mockMvc.perform(post("/api/public/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        long count = userRepo.findAll().stream()
                .filter(u -> username.equals(u.getUsername()))
                .count();
        assertThat(count).as("duplicate-username bug: expect 2 rows until fixed").isEqualTo(2);
    }

    @Test
    void login_validCredentials_returnsJwt() throws Exception {
        String username = uniqueUsername();
        createUser(username); // password is "testpass" (set by createUser helper)

        String body = """
                {"username":"%s","password":"testpass"}
                """.formatted(username);

        String response = mockMvc.perform(post("/api/public/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jwt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        String jwt = objectMapper.readTree(response).get("jwt").asText();
        assertThat(jwtUtil.extractUsername(jwt)).isEqualTo(username);
    }

    @Test
    void login_wrongPassword_returns403() throws Exception {
        String username = uniqueUsername();
        createUser(username);

        String body = """
                {"username":"%s","password":"wrongpass"}
                """.formatted(username);

        // UserController.createAuthenticationToken throws Exception on bad creds.
        // Spring Security's ExceptionTranslationFilter catches it and returns 403 Forbidden
        // rather than letting it propagate as 500. Documenting current behavior.
        mockMvc.perform(post("/api/public/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void login_unknownUsername_returns403() throws Exception {
        String body = """
                {"username":"%s","password":"testpass"}
                """.formatted(uniqueUsername()); // never persisted

        // Same as wrong password: Spring Security returns 403 Forbidden.
        mockMvc.perform(post("/api/public/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void forgotPassword_knownEmail_createsTokenAndCallsEmailService() throws Exception {
        String username = uniqueUsername();
        createUser(username);

        String body = """
                {"email":"%s"}
                """.formatted(username);

        mockMvc.perform(post("/api/public/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        assertThat(tokenRepo.findByEmail(username)).isPresent();
        verify(emailService).sendPasswordResetEmail(eqIgnoreCase(username), anyString());
    }

    @Test
    void forgotPassword_unknownEmail_returnsOkButDoesNothing() throws Exception {
        String unknown = uniqueUsername(); // never persisted
        String body = """
                {"email":"%s"}
                """.formatted(unknown);

        mockMvc.perform(post("/api/public/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // Security property: no token row, no email sent, no info leak.
        assertThat(tokenRepo.findByEmail(unknown)).isEmpty();
        verify(emailService, never()).sendPasswordResetEmail(anyString(), anyString());
    }

    // Helper because Mockito's eq() is strict about casing; users of usernames
    // are case-sensitive in this codebase, so a plain eq() works — alias for clarity.
    private static String eqIgnoreCase(String s) {
        return org.mockito.ArgumentMatchers.eq(s);
    }

    @Test
    void resetPassword_validToken_updatesPasswordAndMarksUsed() throws Exception {
        String username = uniqueUsername();
        User user = createUser(username);
        String originalHash = user.getPassword();

        // Create a token directly via the repo (bypassing forgot-password)
        // so this test only exercises reset-password.
        String token = java.util.UUID.randomUUID().toString();
        PasswordResetToken prt = new PasswordResetToken(token, username);
        tokenRepo.save(prt);

        String body = """
                {"token":"%s","newPassword":"newsecret"}
                """.formatted(token);

        mockMvc.perform(post("/api/public/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        User updated = userRepo.findByUsername(username).orElseThrow();
        assertThat(updated.getPassword()).isNotEqualTo(originalHash);
        assertThat(passwordEncoder.matches("newsecret", updated.getPassword())).isTrue();
    }

    @Test
    void resetPassword_invalidToken_returns400() throws Exception {
        String body = """
                {"token":"not-a-real-token","newPassword":"newsecret"}
                """;

        mockMvc.perform(post("/api/public/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void googleAuth_existingUser_returnsJwtWithoutCreatingNewRow() throws Exception {
        String email = uniqueUsername();
        createUser(email); // pre-existing user

        GoogleIdToken.Payload payload = new GoogleIdToken.Payload();
        payload.setEmail(email);
        payload.set("name", "Google Name");
        when(googleOAuthService.verifyToken("fake-google-token")).thenReturn(payload);

        long countBefore = userRepo.count();

        String body = """
                {"token":"fake-google-token"}
                """;

        mockMvc.perform(post("/api/public/google-auth")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jwt").isNotEmpty())
                .andExpect(jsonPath("$.email").value(email));

        // No new row for an existing user.
        assertThat(userRepo.count()).isEqualTo(countBefore);
    }
}
