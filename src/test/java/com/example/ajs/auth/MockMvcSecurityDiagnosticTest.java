package com.example.ajs.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Diagnostic: proves the security filter chain actually gates requests when
 * MockMvc is built <em>from the web application context</em> with the Spring
 * Security filter chain applied explicitly.
 *
 * <p>The dashboard test relied on {@code @AutoConfigureMockMvc} to install the
 * chain, and that did not gate unauthenticated requests. Building MockMvc by hand
 * with {@code springSecurity()} removes all doubt about which filters are in
 * play, so this test isolates the harness question from the application question.
 */
@SpringBootTest
@ActiveProfiles("test")
class MockMvcSecurityDiagnosticTest {

    @Autowired
    private WebApplicationContext context;

    @Test
    @DisplayName("hand-built MockMvc with springSecurity() rejects unauthenticated requests")
    void explicitSpringSecurityFilterGatesRequests() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup
                        .SecurityMockMvcConfigurers.springSecurity())
                .build();

        mockMvc.perform(get("/overview"))
                .andExpect(status().is4xxClientError());
    }
}
