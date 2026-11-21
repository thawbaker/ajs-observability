package com.example.ajs.auth.domain;

/**
 * An application that can be granted or denied to a user, mirroring the
 * {@code applications} table ported from {@code my-dashboard}.
 *
 * <p>For this deployment an "application" is either one of the AJS dashboards
 * (Overview, Failures, Inventory, ...) or another observed IBM i partition.
 * The table is shared, so both can be modelled without schema change.
 */
public record App(
        long id,
        String name,
        String slug,
        String icon,
        String url,
        boolean enabled
) {
}
