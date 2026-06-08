package com.alexandria.security;

/**
 * Authority names used by Spring Security and seeded in
 * {@code db/changelog/changes/003-seed-roles.yaml}.
 *
 * <p>If you add or rename a constant here, update the Liquibase seed in lockstep —
 * the two are not validated against each other at startup.
 */
public final class RoleNames {

    public static final String USER = "ROLE_USER";
    public static final String ADMIN = "ROLE_ADMIN";

    private RoleNames() {}
}
