package com.example.ajs.auth.repository;

import com.example.ajs.auth.domain.User;

import java.util.List;
import java.util.Optional;

/**
 * User persistence, ported from the {@code users} functions in
 * {@code my-dashboard/src/lib/db.ts}.
 *
 * <p>Backed by SQLite via JDBC. All methods are explicit about their effect on
 * the {@code disabled} flag, because that flag is what terminates a live session
 * (it is re-checked on every request rather than trusted from the JWT).
 */
public interface UserRepository {

    Optional<User> findByEmail(String email);

    Optional<User> findById(long id);

    List<User> findAll();

    /**
     * Inserts a user and returns it with its assigned id.
     *
     * @throws org.springframework.dao.DuplicateKeyException if the email exists
     */
    User create(String email, String passwordHash, String name, User.Role role);

    /** Sets or clears the {@code disabled} flag. Returns true when a row changed. */
    boolean setDisabled(long id, boolean disabled);

    boolean updateRole(long id, User.Role role);

    boolean updatePasswordHash(long id, String passwordHash);

    boolean delete(long id);

    /**
     * Counts enabled admins. Exists to prevent the last admin being disabled,
     * demoted or deleted, which would lock everyone out of administration —
     * a failure mode that is trivial to reach and painful to recover from.
     */
    int countActiveAdmins();
}
