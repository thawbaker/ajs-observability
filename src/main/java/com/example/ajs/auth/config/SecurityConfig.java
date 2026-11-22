package com.example.ajs.auth.config;

import com.example.ajs.auth.repository.UserRepository;
import com.example.ajs.auth.security.JwtAuthenticationFilter;
import com.example.ajs.auth.security.JwtService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security wiring: stateless JWT-cookie authentication, role-gated
 * administration, and protection of the AJS dashboards.
 *
 * <p>Sessions are disabled ({@link SessionCreationPolicy#STATELESS}) because
 * identity travels in the JWT cookie. Nothing about the user is kept in an
 * {@code HttpSession}, so the application can be restarted or scaled without
 * logging anyone out.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** Minimum bcrypt strength. Spring's default is 10; kept explicit. */
    private static final int BCRYPT_STRENGTH = 10;

    @Value("${auth.jwt.secret:}")
    private String jwtSecret;

    /**
     * bcrypt, matching the {@code bcryptjs} hashes the reference Next.js app
     * writes (both produce {@code $2a$10$}). Round 10 is the strength Spring
     * Security defaults to and is the same cost the reference uses.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(BCRYPT_STRENGTH);
    }

    @Bean
    public JwtService jwtService() {
        return new JwtService(jwtSecret);
    }

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(
            JwtService jwtService, UserRepository userRepository) {
        return new JwtAuthenticationFilter(jwtService, userRepository);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           JwtAuthenticationFilter jwtAuthenticationFilter)
            throws Exception {
        http
                // CSRF IS DISABLED, AND THIS IS A KNOWN WEAKNESS.
                //
                // An earlier version of this comment claimed there was "no HTML
                // form POST from a browser session". That was wrong: there are ten
                // browser form POSTs, including destructive admin actions
                // (POST /admin/users/{id}/delete, /disabled, /role, and the app and
                // permission forms). See NOTES.md, design review finding C1.
                //
                // The only thing preventing cross-site forgery of those requests is
                // the session cookie being set SameSite=Lax in AuthController. That
                // mitigation is load-bearing and must not be relaxed to
                // SameSite=None without first enabling CSRF here.
                //
                // The fix is small and worth doing: the templates are server-rendered
                // Thymeleaf, so th:action injects the hidden _csrf field automatically
                // and Spring Security's default token repository works unchanged.
                // Re-enable csrf() and remove this comment.
                .csrf(csrf -> csrf.disable())

                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(auth -> auth
                        // Public: sign-in/sign-up pages and the assets needed to
                        // render them. Everything else requires authentication.
                        .requestMatchers("/sign-in", "/sign-up", "/auth/**").permitAll()
                        .requestMatchers("/css/**", "/js/**", "/favicon.ico", "/error").permitAll()
                        // Health endpoint stays open so a load balancer can probe
                        // without credentials, but only exposes liveness by default.
                        .requestMatchers("/actuator/health").permitAll()
                        // Administration requires the ADMIN role.
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        // Observability dashboards require a signed-in user.
                        // Reads stay open to any authenticated role because
                        // read-only monitoring is the purpose of the application;
                        // per-app denylist filtering refines this per user.
                        .anyRequest().authenticated())

                // API-style 401/403 rather than a login redirect, so a fetch or a
                // curl against a protected path gets a clear status instead of a
                // 302 to an HTML page. Browsers are handled by the redirect below.
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            if (isBrowserRequest(request)) {
                                response.sendRedirect("/sign-in");
                            } else {
                                response.sendError(401, "Unauthorized");
                            }
                        })
                        .accessDeniedHandler((request, response, deniedException) ->
                                response.sendError(403, "Forbidden")))

                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Distinguishes a browser navigation from an API call so the entry point can
     * redirect humans to the sign-in page while still returning 401 to clients.
     */
    private static boolean isBrowserRequest(jakarta.servlet.http.HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains("text/html");
    }
}
