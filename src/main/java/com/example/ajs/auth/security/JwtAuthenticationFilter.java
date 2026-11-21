package com.example.ajs.auth.security;

import com.example.ajs.auth.domain.User;
import com.example.ajs.auth.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Reads the session JWT from the {@code auth-token} cookie and, if it verifies,
 * populates the Spring Security context.
 *
 * <h2>The database re-check is the point</h2>
 * The token carries a role, but the role is <em>not</em> trusted. The user is
 * re-read from SQLite on every request and the request is rejected if the
 * account is missing or disabled. This mirrors {@code getSessionUser()} in
 * {@code my-dashboard/src/lib/auth.ts} and is what makes "disable this user"
 * take effect immediately rather than whenever their 24-hour token expires.
 *
 * <p>Authorities are built from the <em>database</em> role, so a demotion from
 * admin to user removes access on the very next request.
 *
 * <p>This filter never rejects a request on its own. It either populates the
 * context or leaves it empty, and the authorisation rules decide what an
 * anonymous visitor may see. Rejecting here would turn a bad cookie into an
 * error page instead of a sign-in redirect.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public JwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        // Only authenticate if nothing already has. Avoids clobbering a context
        // set by another mechanism and makes the filter safe to reorder.
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            readToken(request)
                    .flatMap(jwtService::verify)
                    .flatMap(claims -> userRepository.findById(claims.userId()))
                    // Disabled or deleted users are treated exactly like no token.
                    .filter(User::isActive)
                    .ifPresent(user -> authenticate(request, user));
        }

        filterChain.doFilter(request, response);
    }

    private Optional<String> readToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(c -> JwtService.COOKIE_NAME.equals(c.getName()))
                .map(Cookie::getValue)
                .filter(v -> v != null && !v.isBlank())
                .findFirst();
    }

    private void authenticate(HttpServletRequest request, User user) {
        // Authority naming matches Spring Security's ROLE_ convention so that
        // hasRole('ADMIN') works without a custom expression handler.
        List<SimpleGrantedAuthority> authorities = List.of(
                new SimpleGrantedAuthority("ROLE_" + user.role().name()));

        // The principal is the User record itself, so controllers can read the
        // current account without another query. The hash is not exposed.
        var authentication = new UsernamePasswordAuthenticationToken(user, null, authorities);
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
