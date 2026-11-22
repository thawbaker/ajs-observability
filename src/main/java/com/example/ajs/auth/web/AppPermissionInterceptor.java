package com.example.ajs.auth.web;

import com.example.ajs.auth.domain.App;
import com.example.ajs.auth.domain.User;
import com.example.ajs.auth.repository.AppRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.Optional;

/**
 * Enforces the per-user app denylist on the dashboard routes.
 *
 * <h2>Why this exists</h2>
 * The permission model is a denylist: a user with no deny rows sees every
 * enabled app. Without this interceptor the model was decorative — an
 * administrator could deny a user the Failures page, the admin UI would show
 * "no access", and the user could still open it. Storing a restriction that is
 * not enforced is worse than not offering it, because it reads as a control that
 * exists.
 *
 * <p>Maps request paths to app slugs, then applies <strong>two</strong> checks to
 * the authenticated user: the app must be <em>enabled</em>, and the user must not
 * hold a deny row for it. The enabled check was added later — without it, a
 * disabled app was dropped from {@code AppRepository.accessibleApps()} and from
 * the navigation but its route still answered 200, making {@code /admin/apps} a
 * control in appearance only.
 *
 * <p>Administrators bypass both checks: they must be able to reach every page in
 * order to administer it, and locking an admin out of a dashboard — or out of a
 * disabled app they need to re-enable — would be a support problem with no
 * in-product remedy.
 *
 * <p>Routes not present in the map are unaffected. That is deliberate — the
 * denylist governs observability surfaces, not authentication or administration
 * routes, so a missing entry fails open rather than blocking sign-in.
 */
public class AppPermissionInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AppPermissionInterceptor.class);

    /**
     * Route prefix to app slug. Must stay in step with <strong>two</strong> other
     * places, or the control silently does not exist:
     *
     * <ul>
     *   <li>{@code AuthBootstrap.BUILT_IN_APPS} — registers the slug, so there is
     *       an app row to deny against.</li>
     *   <li>{@code WebMvcConfig.addPathPatterns} — registers the path, so this
     *       interceptor is invoked at all. A route in this map but absent there
     *       is never checked, which is how the timeline was ungovernable (C8).</li>
     * </ul>
     */
    private static final Map<String, String> ROUTE_TO_SLUG = Map.of(
            "/overview", "overview",
            "/calendar", "calendar",
            "/timeline", "timeline",
            "/failures", "failures",
            "/jobs", "jobs",
            "/attention", "attention"
    );

    private final AppRepository apps;

    public AppPermissionInterceptor(AppRepository apps) {
        this.apps = apps;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {

        String path = request.getRequestURI();

        String slug = resolveSlug(path);
        if (slug == null) {
            return true; // not a permission-governed route
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof User user)) {
            // No identity: the security filter chain already handles this, and
            // returning true here avoids turning an auth problem into a 403.
            return true;
        }

        // Administrators bypass app-level restrictions. They can reach every
        // dashboard; the denylist is a user-level control.
        if (user.isAdmin()) {
            return true;
        }

        Optional<App> registered = apps.findAppBySlug(slug);
        if (registered.isEmpty()) {
            // The app is not registered, so there is nothing to deny against.
            // Fail open: an unregistered route is a configuration question, not
            // a reason to block a signed-in user.
            log.debug("No registered application for slug '{}'; allowing", slug);
            return true;
        }

        App app = registered.get();

        // A disabled app must be unreachable, not merely hidden from the menu.
        //
        // Without this check, disabling an app in /admin/apps only removed it
        // from navigations: the URL still answered 200 for any signed-in user.
        // That made the control cosmetic, which is the same failure the denylist
        // check below exists to prevent — a restriction that reads as enforced
        // but is not. AppRepository.accessibleApps() had always excluded disabled
        // apps from the list; the interceptor simply never asked.
        //
        // Administrators are exempt for the same reason they bypass the denylist:
        // re-enabling a disabled app is an admin action, and locking admins out
        // of the page they need to fix it would have no in-product remedy.
        if (!app.enabled()) {
            log.debug("Denied user {} access to disabled app '{}'", user.email(), slug);
            response.sendError(HttpServletResponse.SC_FORBIDDEN,
                    "You do not have access to this application");
            return false;
        }

        if (apps.blockedAppIds(user.id()).contains(app.id())) {
            log.debug("Denied user {} access to app '{}' (denylist)", user.email(), slug);
            response.sendError(HttpServletResponse.SC_FORBIDDEN,
                    "You do not have access to this application");
            return false;
        }

        return true;
    }

    /**
     * Maps a path to a slug, tolerating sub-paths.
     *
     * <p>Sub-paths matter: {@code /jobs/PAYROLL1} is the job detail page and must
     * be governed by the same "jobs" permission as {@code /jobs}, otherwise the
     * restriction is bypassed simply by navigating one level deeper.
     */
    private String resolveSlug(String path) {
        for (Map.Entry<String, String> entry : ROUTE_TO_SLUG.entrySet()) {
            String prefix = entry.getKey();
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return entry.getValue();
            }
        }
        return null;
    }
}
