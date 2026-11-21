package com.example.ajs.auth.web;

import com.example.ajs.auth.domain.User;
import com.example.ajs.auth.security.JwtService;
import com.example.ajs.auth.service.AuthService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Sign-in, sign-up and sign-out. Server-rendered Thymeleaf, consistent with the
 * rest of the application.
 *
 * <p>Sign-out is done with a POST rather than a GET link so that a prefetching
 * browser or a crawler cannot log a user out by following a link. The pages
 * therefore use a small form button rather than an anchor.
 */
@Controller
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/sign-in")
    public String signInPage(@AuthenticationPrincipal User currentUser) {
        // Already signed in: no reason to show the form.
        if (currentUser != null) {
            return "redirect:/overview";
        }
        return "auth/sign-in";
    }

    @PostMapping("/auth/sign-in")
    public String signIn(@RequestParam String email,
                         @RequestParam String password,
                         HttpServletResponse response,
                         Model model) {

        AuthService.SignInResult result = authService.signIn(email, password);

        return switch (result) {
            case AuthService.SignInResult.Success success -> {
                setSessionCookie(response, success.token());
                yield "redirect:/overview";
            }
            // Distinct messages, matching the reference implementation. See the
            // enumeration trade-off documented on AuthService.
            case AuthService.SignInResult.EmailNotFound ignored ->
                    error(model, "auth/sign-in", "No account found with this email address", email);
            case AuthService.SignInResult.AccountDisabled ignored ->
                    error(model, "auth/sign-in", "This account has been disabled", email);
            case AuthService.SignInResult.InvalidPassword ignored ->
                    error(model, "auth/sign-in", "Incorrect password", email);
        };
    }

    @GetMapping("/sign-up")
    public String signUpPage(@AuthenticationPrincipal User currentUser) {
        if (currentUser != null) {
            return "redirect:/overview";
        }
        return "auth/sign-up";
    }

    @PostMapping("/auth/sign-up")
    public String signUp(@RequestParam String email,
                         @RequestParam String password,
                         @RequestParam String name,
                         HttpServletResponse response,
                         Model model) {

        AuthService.SignUpResult result = authService.signUp(email, password, name);

        return switch (result) {
            case AuthService.SignUpResult.Success success -> {
                // Sign the new account straight in, as the reference does.
                setSessionCookie(response, success.token());
                yield "redirect:/overview";
            }
            case AuthService.SignUpResult.EmailTaken ignored ->
                    error(model, "auth/sign-up", "An account with that email already exists", email, name);
            case AuthService.SignUpResult.Invalid invalid ->
                    error(model, "auth/sign-up", invalid.reason(), email, name);
        };
    }

    @PostMapping("/auth/sign-out")
    public String signOut(HttpServletResponse response) {
        clearSessionCookie(response);
        return "redirect:/sign-in";
    }

    /**
     * JSON view of the current session, mirroring {@code /api/auth/me} in the
     * reference app so other clients can check identity.
     */
    @GetMapping("/api/auth/me")
    public org.springframework.http.ResponseEntity<?> me(
            @AuthenticationPrincipal User user, HttpServletRequest request) {
        if (user == null) {
            return org.springframework.http.ResponseEntity.status(401)
                    .body(java.util.Map.of("error", "Unauthorized"));
        }
        return org.springframework.http.ResponseEntity.ok(java.util.Map.of(
                "id", user.id(),
                "email", user.email(),
                "name", user.name(),
                "role", user.role().dbValue()));
    }

    // --- helpers -----------------------------------------------------------

    private void setSessionCookie(HttpServletResponse response, String token) {
        Cookie cookie = new Cookie(JwtService.COOKIE_NAME, token);
        cookie.setHttpOnly(true);          // not readable by JavaScript
        cookie.setSecure(false);           // set true behind HTTPS; see application.yml
        cookie.setPath("/");
        // 24 hours, matching the token TTL so the cookie cannot outlive the JWT.
        cookie.setMaxAge((int) (JwtService.TOKEN_TTL_MILLIS / 1000));
        // Lax, matching the reference. Combined with the CSRF note in
        // SecurityConfig, this is the request-forgery protection in place.
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
    }

    private void clearSessionCookie(HttpServletResponse response) {
        Cookie cookie = new Cookie(JwtService.COOKIE_NAME, "");
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(0); // expire immediately
        response.addCookie(cookie);
    }

    private String error(Model model, String view, String message) {
        return error(model, view, message, null, null);
    }

    private String error(Model model, String view, String message, String email) {
        return error(model, view, message, email, null);
    }
    /** Re-renders the form with the error, preserving what the user typed. */
    private String error(Model model, String view, String message, String email, String name) {
        model.addAttribute("error", message);
        if (email != null) {
            model.addAttribute("email", email);
        }
        if (name != null) {
            model.addAttribute("name", name);
        }
        return view;
    }
}
