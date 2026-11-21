package com.example.ajs.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the security wiring itself.
 *
 * <p>This exists because of a real near-miss: the MockMvc dashboard tests were
 * passing 200 for unauthenticated requests while the running application
 * correctly returned 401. The difference was in the test harness, not the
 * application, but the lesson is that "the tests are green" is not evidence the
 * gate is closed. This test asserts the chain exists in the context, so a future
 * change that silently removes it fails loudly and immediately.
 */
@SpringBootTest
@ActiveProfiles("test")
class SecurityWiringTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("a SecurityFilterChain bean is registered")
    void securityFilterChainIsRegistered() {
        assertThat(context.getBeanNamesForType(SecurityFilterChain.class))
                .as("SecurityConfig must contribute a filter chain; if this is empty, "
                        + "the application is unsecured")
                .isNotEmpty();
    }

    @Test
    @DisplayName("springSecurityFilterChain exists, so the servlet filter is installed")
    void springSecurityFilterIsInstalled() {
        assertThat(context.containsBean("springSecurityFilterChain"))
                .as("Spring Security's DelegatingFilterProxy target must be present")
                .isTrue();
    }

    @Test
    @DisplayName("the encoder is bcrypt, not a no-op or plaintext encoder")
    void passwordEncoderIsBcrypt() {
        var encoder = context.getBean(org.springframework.security.crypto.password.PasswordEncoder.class);
        String hash = encoder.encode("hunter2");
        assertThat(hash).as("bcrypt hashes start with $2a/$2b/$2y").startsWith("$2");
        assertThat(encoder.matches("hunter2", hash)).isTrue();
        assertThat(encoder.matches("wrong", hash)).isFalse();
    }
}
