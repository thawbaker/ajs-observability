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
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.web.filter.OncePerRequestFilter;

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
                // CSRF protection is ON (design review finding C1, now fixed).
                //
                // This was previously disabled on a justification that was simply
                // false — that there was "no HTML form POST from a browser session".
                // There are ten browser form POSTs, including destructive admin
                // actions (POST /admin/users/{id}/delete, /disabled, /role, plus the
                // app and permission forms). See NOTES.md section 8.
                //
                // The repository is CookieCsrfTokenRepository, NOT the default
                // HttpSession-backed one, and that choice is load-bearing.
                //
                // Using csrf(Customizer.withDefaults()) is the obvious fix and is
                // wrong here: with sessionCreationPolicy(STATELESS) above, the
                // default HttpSessionCsrfTokenRepository writes the token into a
                // session that is never retained. The token still renders into every
                // form, because th:action reads it during rendering, so the HTML looks
                // perfectly correct while every submission 403s for a real browser.
                // The cookie repository keeps the token client-side, which is the
                // supported pairing for a stateless chain. HttpOnly is left off
                // because the value must be readable to be replayed on the next
                // request; the token is not a credential on its own.
                //
                // Every form in src/main/resources/templates uses th:action, so
                // Thymeleaf injects the hidden _csrf field automatically and no
                // template needed editing.
                //
                // SameSite=Lax on the session cookie (AuthController) remains in
                // place as defence in depth, but it is no longer the only thing
                // standing between an attacker and those ten endpoints. That matters
                // because SameSite is a browser-side control that disappears the
                // moment the cookie policy is relaxed for a cross-site deployment;
                // this token check does not.
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        // Force the token to be resolved on EVERY request, not just on
                        // the unsafe one. See the note below: without this the cookie
                        // is never written on a GET and no first POST can succeed.
                        .csrfTokenRequestHandler(tokenRequestHandler()))

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

                // Runs after CsrfFilter (which registers the attribute) and before
                // the request reaches the view, so the cookie is on the response by
                // the time anything is rendered.
                .addFilterAfter(csrfCookieEnsuringFilter(),
                        org.springframework.security.web.csrf.CsrfFilter.class)

                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Makes the CSRF token resolve eagerly, so the {@code XSRF-TOKEN} cookie is
     * written on every response rather than only on an unsafe request.
     *
     * <p>This is required, and its absence was a real defect caught only by driving
     * a real browser. Spring Security 6 defers token loading: the token is resolved
     * when it is needed for an unsafe request, or when something reads the request
     * attribute. Rendering the hidden {@code _csrf} field is enough to put a
     * <em>valid-looking</em> token in the HTML — which is exactly why this hid so
     * well — but with a stateless chain there is nothing to persist it to, so the
     * cookie is never sent. A browser then has the form field and no cookie, the
     * double-submit check fails, and <strong>every form on the site 403s</strong>
     * while the pages look perfectly correct.
     *
     * <p>Setting {@code csrfRequestAttributeName} to {@code null} means the attribute
     * name cannot be known without loading the token, so the token is loaded on
     * every request and the cookie is written. This is the configuration Spring's
     * own reference documents for exactly this case.
     *
     * <p>Note the two failures this single feature has produced, because they are
     * the reason the comment is this long. First the token repository was the
     * default session-backed one, which cannot work on a stateless chain at all.
     * Then it was cookie-backed but still deferred, which fails on the first POST
     * only. Both rendered a correct-looking form and passed the unit tests.
     * {@code CsrfProtectionTest} now asserts the cookie is actually issued on a
     * GET, which is the assertion that would have caught each of them.
     */
    private static XorCsrfTokenRequestAttributeHandler tokenRequestHandler() {
        XorCsrfTokenRequestAttributeHandler handler = new XorCsrfTokenRequestAttributeHandler();
        handler.setCsrfRequestAttributeName(null);
        return handler;
    }

    /**
     * Forces the CSRF token to be materialised, and therefore its cookie to be
     * written, on <em>every</em> request.
     *
     * <p>Why this is needed rather than relying on the request handler alone, which
     * was tried first and does not work. The repository is wrapped in
     * {@code LazyCsrfTokenRepository.SaveOnAccessCsrfToken}, whose
     * {@code saveTokenIfNecessary()} is called from {@code getToken()} and
     * <strong>only</strong> from there — {@code getParameterName()} and
     * {@code getHeaderName()} do not trigger it. The deferred handler calls
     * {@code getParameterName()} to decide which request attribute to set, so it
     * populates the attribute without ever persisting the cookie. Reading
     * {@code getToken()} here is what actually writes it.
     *
     * <p>That distinction is invisible from the outside and is the reason this
     * defect survived two rounds of fixing: the hidden {@code _csrf} field renders
     * correctly either way, so every page looks right while the cookie is missing.
     * The page then fails the double submit on its first POST.
     */
    private static OncePerRequestFilter csrfCookieEnsuringFilter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest request,
                                            jakarta.servlet.http.HttpServletResponse response,
                                            jakarta.servlet.FilterChain chain)
                    throws jakarta.servlet.ServletException, java.io.IOException {
                Object attribute = request.getAttribute(CsrfToken.class.getName());
                if (attribute instanceof CsrfToken token) {
                    // Accessing the value is the point: it is the call that persists.
                    token.getToken();
                }
                chain.doFilter(request, response);
            }
        };
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
