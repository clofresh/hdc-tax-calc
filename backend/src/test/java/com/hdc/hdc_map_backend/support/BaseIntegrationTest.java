package com.hdc.hdc_map_backend.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hdc.hdc_map_backend.entity.User;
import com.hdc.hdc_map_backend.repository.user.UserRepo;
import com.hdc.hdc_map_backend.util.JwtUtil;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class BaseIntegrationTest {

    @Autowired protected MockMvc mockMvc;
    @Autowired protected ObjectMapper objectMapper;
    @Autowired protected JwtUtil jwtUtil;
    @Autowired protected UserRepo userRepo;
    @Autowired protected PasswordEncoder passwordEncoder;
    @Autowired protected DataSource dataSource;

    /** Safety: refuse to run tests against any DB that isn't hdc_test. */
    @BeforeEach
    void assertTestDatabase() throws SQLException {
        try (var c = dataSource.getConnection()) {
            String url = c.getMetaData().getURL();
            if (!url.contains("hdc_test")) {
                throw new IllegalStateException(
                        "Integration tests must run against hdc_test. Got: " + url);
            }
        }
    }

    /** Convenience: persist a User with a BCrypt-hashed password "testpass". */
    protected User createUser(String username) {
        User u = new User();
        u.setUsername(username);
        u.setPassword(passwordEncoder.encode("testpass"));
        u.setRole("USER");
        u.setFullName("Test " + username);
        return userRepo.save(u);
    }

    protected String bearerFor(User user) {
        return JwtTestHelper.bearer(jwtUtil, user);
    }
}
