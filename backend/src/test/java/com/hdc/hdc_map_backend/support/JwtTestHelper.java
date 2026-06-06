package com.hdc.hdc_map_backend.support;

import com.hdc.hdc_map_backend.entity.User;
import com.hdc.hdc_map_backend.util.JwtUtil;
import java.util.Collections;
import org.springframework.security.core.userdetails.UserDetails;

/** Static helper: mints JWTs for integration tests using the production JwtUtil. */
public final class JwtTestHelper {
    private JwtTestHelper() {}

    public static String tokenFor(JwtUtil jwtUtil, User user) {
        UserDetails ud = new org.springframework.security.core.userdetails.User(
                user.getUsername(), user.getPassword(), Collections.emptyList());
        return jwtUtil.generateToken(ud);
    }

    public static String bearer(JwtUtil jwtUtil, User user) {
        return "Bearer " + tokenFor(jwtUtil, user);
    }
}
