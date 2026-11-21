package com.example.ajs.auth.web;

import com.example.ajs.auth.domain.App;
import com.example.ajs.auth.domain.User;
import com.example.ajs.auth.repository.AppRepository;
import com.example.ajs.auth.service.AuthService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Administration: user accounts, roles, disabled state, and per-user app
 * permissions.
 *
 * <p>Guarded twice, deliberately:
 * <ol>
 *   <li>{@code /admin/**} requires {@code ROLE_ADMIN} in {@link
 *       com.example.ajs.auth.config.SecurityConfig}, and</li>
 *   <li>{@link PreAuthorize} on this class re-asserts it at the method level.</li>
 * </ol>
 * The second check is not redundant defensiveness for its own sake: the URL rule
 * is a single string that is easy to break when routes are reorganised, and the
 * consequence of breaking it is that any signed-in user can grant themselves
 * admin. Two independent gates make that mistake much harder to make.
 *
 * <p>The current admin's role is read from the database, not the token, so a
 * demotion takes effect on the next request.
 */
@Controller
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final AuthService authService;
    private final AppRepository apps;

    public AdminController(AuthService authService, AppRepository apps) {
        this.authService = authService;
        this.apps = apps;
    }

    /** User list, with each user's denied-app count and disabled state. */
    @GetMapping("/admin/users")
    public String users(Model model, @AuthenticationPrincipal User currentUser) {
        List<User> all = authService.listUsers();

        // Deny counts are shown rather than "app count", because the model is a
        // denylist: a count of granted apps would be "total minus denied" and
        // would read as though permissions had been individually assigned.
        Map<Long, Integer> deniedCounts = new LinkedHashMap<>();
        for (User u : all) {
            deniedCounts.put(u.id(), apps.blockedAppIds(u.id()).size());
        }

        model.addAttribute("users", all);
        model.addAttribute("deniedCounts", deniedCounts);
        model.addAttribute("totalApps", apps.findEnabledApps().size());
        model.addAttribute("currentUserId", currentUser == null ? -1L : currentUser.id());
        model.addAttribute("active", "admin-users");
        return "auth/admin-users";
    }

    @PostMapping("/admin/users/{id}/disabled")
    public String setDisabled(@PathVariable long id,
                              @RequestParam boolean disabled,
                              Model model,
                              @AuthenticationPrincipal User currentUser) {
        // Refuse self-disable here as well as in the service. The service guard
        // covers "last admin"; this covers the ordinary admin locking themselves
        // out of the very page they are standing on.
        if (currentUser != null && currentUser.id() == id && disabled) {
            return redirectWithError("users", "You cannot disable your own account");
        }

        AuthService.AdminChangeResult result = authService.setDisabled(id, disabled);
        return switch (result) {
            case AuthService.AdminChangeResult.Ok ignored -> "redirect:/admin/users";
            case AuthService.AdminChangeResult.NotFound ignored ->
                    redirectWithError("users", "That user no longer exists");
            case AuthService.AdminChangeResult.LastAdmin ignored ->
                    redirectWithError("users", "Refused: this is the last enabled administrator");
        };
    }

    @PostMapping("/admin/users/{id}/role")
    public String setRole(@PathVariable long id,
                          @RequestParam String role,
                          @AuthenticationPrincipal User currentUser) {
        User.Role parsed = User.Role.from(role);

        if (currentUser != null && currentUser.id() == id && parsed != User.Role.ADMIN) {
            return redirectWithError("users", "You cannot remove your own administrator role");
        }

        AuthService.AdminChangeResult result = authService.updateRole(id, parsed);
        return switch (result) {
            case AuthService.AdminChangeResult.Ok ignored -> "redirect:/admin/users";
            case AuthService.AdminChangeResult.NotFound ignored ->
                    redirectWithError("users", "That user no longer exists");
            case AuthService.AdminChangeResult.LastAdmin ignored ->
                    redirectWithError("users", "Refused: this is the last enabled administrator");
        };
    }

    @PostMapping("/admin/users/{id}/delete")
    public String delete(@PathVariable long id, @AuthenticationPrincipal User currentUser) {
        if (currentUser != null && currentUser.id() == id) {
            return redirectWithError("users", "You cannot delete your own account");
        }
        AuthService.AdminChangeResult result = authService.deleteUser(id);
        return switch (result) {
            case AuthService.AdminChangeResult.Ok ignored -> "redirect:/admin/users";
            case AuthService.AdminChangeResult.NotFound ignored ->
                    redirectWithError("users", "That user no longer exists");
            case AuthService.AdminChangeResult.LastAdmin ignored ->
                    redirectWithError("users", "Refused: this is the last enabled administrator");
        };
    }

    @PostMapping("/admin/users/create")
    public String createUser(@RequestParam String email,
                             @RequestParam String password,
                             @RequestParam String name,
                             @RequestParam(defaultValue = "user") String role) {

        AuthService.SignUpResult result =
                authService.signUp(email, password, name, User.Role.from(role));

        return switch (result) {
            case AuthService.SignUpResult.Success ignored -> "redirect:/admin/users";
            case AuthService.SignUpResult.EmailTaken ignored ->
                    redirectWithError("users", "A user with that email already exists");
            case AuthService.SignUpResult.Invalid invalid ->
                    redirectWithError("users", invalid.reason());
        };
    }

    /** Per-user app permissions. Checked apps are DENIED, per the denylist model. */
    @GetMapping("/admin/users/{id}/permissions")
    public String permissions(@PathVariable long id, Model model) {
        User user = authService.findById(id).orElse(null);
        if (user == null) {
            return redirectWithError("users", "That user no longer exists");
        }

        List<App> allApps = apps.findAllApps();
        Set<Long> denied = Set.copyOf(apps.blockedAppIds(id));

        model.addAttribute("user", user);
        model.addAttribute("apps", allApps);
        model.addAttribute("denied", denied);
        model.addAttribute("accessible", apps.accessibleApps(id));
        model.addAttribute("active", "admin-users");
        return "auth/admin-permissions";
    }

    @PostMapping("/admin/users/{id}/permissions")
    public String setPermissions(@PathVariable long id,
                                 @RequestParam(required = false) List<Long> blocked) {
        // Absent checkboxes submit nothing, so a null list means "nothing denied"
        // i.e. full access. That is the correct reading of the denylist model.
        apps.setBlockedAppIds(id, blocked == null ? List.of() : blocked);
        return "redirect:/admin/users/" + id + "/permissions";
    }

    /** Application registry. */
    @GetMapping("/admin/apps")
    public String appList(Model model) {
        model.addAttribute("apps", apps.findAllApps());
        // Show which users are denied each app, so a deny row is never invisible.
        Map<Long, List<String>> deniedBy = new LinkedHashMap<>();
        for (App app : apps.findAllApps()) {
            deniedBy.put(app.id(), usersDenied(app.id()));
        }
        model.addAttribute("deniedBy", deniedBy);
        model.addAttribute("active", "admin-apps");
        return "auth/admin-apps";
    }

    @PostMapping("/admin/apps/create")
    public String createApp(@RequestParam String name,
                            @RequestParam String slug,
                            @RequestParam(defaultValue = "layout-grid") String icon,
                            @RequestParam(defaultValue = "#") String url) {
        apps.createApp(name, slug, icon, url);
        return "redirect:/admin/apps";
    }

    @PostMapping("/admin/apps/{id}/enabled")
    public String setAppEnabled(@PathVariable long id, @RequestParam boolean enabled) {
        apps.setAppEnabled(id, enabled);
        return "redirect:/admin/apps";
    }

    private List<String> usersDenied(long appId) {
        return authService.listUsers().stream()
                .filter(u -> apps.blockedAppIds(u.id()).contains(appId))
                .map(User::email)
                .collect(Collectors.toList());
    }

    private String redirectWithError(String section, String message) {
        // Flash-free: the message rides as a query parameter. Kept simple on
        // purpose; if error reporting grows, switch to RedirectAttributes.
        return "redirect:/admin/" + section + "?error="
                + java.net.URLEncoder.encode(message, java.nio.charset.StandardCharsets.UTF_8);
    }
}
