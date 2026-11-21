package com.example.ajs.auth.domain;

/**
 * A user account, mirroring the {@code users} table ported from
 * {@code my-dashboard}.
 *
 * <p>{@code passwordHash} is a bcrypt hash ({@code $2a$10$...}) produced by
 * Spring Security's {@code BCryptPasswordEncoder}, which is byte-compatible with
 * the {@code bcryptjs} hashes the Next.js app writes. A user row can therefore
 * be moved between the two applications without a password reset.
 *
 * <p>Note what is <em>not</em> in the JWT: {@code disabled}. It is read from the
 * database on every request so that disabling an account takes effect
 * immediately rather than when the token happens to expire. See
 * {@link com.example.ajs.auth.security.JwtService}.
 */
public record User(
        long id,
        String email,
        String passwordHash,
        String name,
        Role role,
        boolean disabled,
        String createdAt
) {

    /** The two roles permitted by the {@code users.role} CHECK constraint. */
    public enum Role {
        USER,
        ADMIN;

        /** Parses the stored lowercase text, defaulting to the least privilege. */
        public static Role from(String stored) {
            if (stored == null) {
                return USER;
            }
            return switch (stored.trim().toLowerCase()) {
                case "admin" -> ADMIN;
                case "user" -> USER;
                // Least privilege on anything unrecognised. A malformed role
                // must never grant admin.
                default -> USER;
            };
        }

        public String dbValue() {
            return name().toLowerCase();
        }
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }

    /**
     * A user who may authenticate: not disabled. Deliberately a separate concept
     * from {@link #isAdmin()} so the two checks cannot be conflated.
     */
    public boolean isActive() {
        return !disabled;
    }
}
