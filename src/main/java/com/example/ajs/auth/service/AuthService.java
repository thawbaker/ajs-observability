package com.example.ajs.auth.service;

import com.example.ajs.auth.domain.User;
import com.example.ajs.auth.repository.UserRepository;
import com.example.ajs.auth.security.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Account operations: sign-up, sign-in, and admin user management.
 *
 * <p>Ported from the equivalent flows in {@code my-dashboard}, preserving its
 * distinct failure modes so the UI can say something useful instead of a generic
 * "login failed":
 * <ul>
 *   <li>unknown email,</li>
 *   <li>disabled account,</li>
 *   <li>wrong password.</li>
 * </ul>
 *
 * <p><strong>Enumeration trade-off, stated deliberately.</strong> Revealing
 * "no account found" versus "incorrect password" lets an attacker probe which
 * email addresses exist. The reference implementation does this on purpose to
 * give users actionable errors, and that behaviour is preserved here for parity.
 * If this system ever faces the public internet, collapse the two messages into
 * one and log the distinction server-side instead.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    /** Outcome of a sign-in attempt, mirroring the reference error taxonomy. */
    public sealed interface SignInResult {
        /** Success — the caller sets the cookie. */
        record Success(User user, String token) implements SignInResult {
        }

        record EmailNotFound() implements SignInResult {
        }

        record AccountDisabled() implements SignInResult {
        }

        record InvalidPassword() implements SignInResult {
        }
    }

    /** Outcome of a sign-up attempt. */
    public sealed interface SignUpResult {
        record Success(User user, String token) implements SignUpResult {
        }

        record EmailTaken() implements SignUpResult {
        }

        record Invalid(String reason) implements SignUpResult {
        }
    }

    // --- sign-in -----------------------------------------------------------

    public SignInResult signIn(String email, String rawPassword) {
        Optional<User> found = users.findByEmail(email);

        if (found.isEmpty()) {
            return new SignInResult.EmailNotFound();
        }
        User user = found.get();

        // Checked before the password so a disabled account cannot be probed for
        // a valid password, and so the user gets the real reason.
        if (user.disabled()) {
            return new SignInResult.AccountDisabled();
        }
        if (!passwordEncoder.matches(rawPassword, user.passwordHash())) {
            return new SignInResult.InvalidPassword();
        }
        return new SignInResult.Success(user, jwtService.createToken(user));
    }

    // --- sign-up -----------------------------------------------------------

    /**
     * Registers a user. Self-service sign-up always creates a {@code USER};
     * there is no path from the public form to an admin account.
     */
    public SignUpResult signUp(String email, String rawPassword, String name) {
        return signUp(email, rawPassword, name, User.Role.USER);
    }

    public SignUpResult signUp(String email, String rawPassword, String name, User.Role role) {
        String problem = validate(email, rawPassword, name);
        if (problem != null) {
            return new SignUpResult.Invalid(problem);
        }
        if (users.findByEmail(email).isPresent()) {
            return new SignUpResult.EmailTaken();
        }

        try {
            User created = users.create(email, passwordEncoder.encode(rawPassword), name.trim(), role);
            return new SignUpResult.Success(created, jwtService.createToken(created));
        } catch (DuplicateKeyException ex) {
            // Lost a race with a concurrent sign-up for the same email. The
            // UNIQUE index is the real guard; this just reports it cleanly.
            return new SignUpResult.EmailTaken();
        }
    }

    /**
     * Shared input validation. Length limits are deliberately modest and match
     * the reference schemas so both applications accept the same passwords.
     */
    private String validate(String email, String rawPassword, String name) {
        if (email == null || email.isBlank()) {
            return "Email is required";
        }
        if (!email.contains("@") || email.startsWith("@") || email.endsWith("@")) {
            return "Email is not valid";
        }
        if (rawPassword == null || rawPassword.length() < 8) {
            return "Password must be at least 8 characters";
        }
        if (name == null || name.isBlank()) {
            return "Name is required";
        }
        return null;
    }

    // --- admin user management ---------------------------------------------

    public List<User> listUsers() {
        return users.findAll();
    }

    public Optional<User> findById(long id) {
        return users.findById(id);
    }

    /** Result of an administrative change that could lock everyone out. */
    public sealed interface AdminChangeResult {
        record Ok(User user) implements AdminChangeResult {
        }

        record NotFound() implements AdminChangeResult {
        }

        /** Refused: it would remove the last enabled administrator. */
        record LastAdmin() implements AdminChangeResult {
        }
    }

    public AdminChangeResult setDisabled(long id, boolean disabled) {
        Optional<User> found = users.findById(id);
        if (found.isEmpty()) {
            return new AdminChangeResult.NotFound();
        }
        User user = found.get();

        if (disabled && wouldRemoveLastAdmin(user)) {
            log.warn("Refused to disable user {} ({}): it is the last enabled admin", id, user.email());
            return new AdminChangeResult.LastAdmin();
        }

        users.setDisabled(id, disabled);
        return users.findById(id)
                .<AdminChangeResult>map(AdminChangeResult.Ok::new)
                .orElseGet(AdminChangeResult.NotFound::new);
    }

    public AdminChangeResult updateRole(long id, User.Role role) {
        Optional<User> found = users.findById(id);
        if (found.isEmpty()) {
            return new AdminChangeResult.NotFound();
        }
        User user = found.get();

        // Demoting the last admin is as fatal as disabling them.
        if (role == User.Role.USER && wouldRemoveLastAdmin(user)) {
            log.warn("Refused to demote user {} ({}): it is the last enabled admin", id, user.email());
            return new AdminChangeResult.LastAdmin();
        }

        users.updateRole(id, role);
        return users.findById(id)
                .<AdminChangeResult>map(AdminChangeResult.Ok::new)
                .orElseGet(AdminChangeResult.NotFound::new);
    }

    public AdminChangeResult deleteUser(long id) {
        Optional<User> found = users.findById(id);
        if (found.isEmpty()) {
            return new AdminChangeResult.NotFound();
        }
        if (wouldRemoveLastAdmin(found.get())) {
            log.warn("Refused to delete user {}: it is the last enabled admin", id);
            return new AdminChangeResult.LastAdmin();
        }
        users.delete(id);
        return new AdminChangeResult.Ok(found.get());
    }

    /**
     * True when acting on this user would leave no enabled administrator.
     *
     * <p>Only an enabled admin counts towards the total, so acting on an already
     * disabled admin is allowed — otherwise a disabled admin row could never be
     * cleaned up.
     */
    private boolean wouldRemoveLastAdmin(User user) {
        return user.isAdmin() && user.isActive() && users.countActiveAdmins() <= 1;
    }
}
