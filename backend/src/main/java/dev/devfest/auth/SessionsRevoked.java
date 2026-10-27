package dev.devfest.auth;

import java.util.List;
import java.util.UUID;

/**
 * Published by {@link AuthService} whenever sessions are revoked, inside the revoking transaction. Listeners that
 * act on it (closing live event streams) run after commit, so a rolled-back revocation closes nothing.
 */
public record SessionsRevoked(List<UUID> sessions, SessionEnd reason) {}
