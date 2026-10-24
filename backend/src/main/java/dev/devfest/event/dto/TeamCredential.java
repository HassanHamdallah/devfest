package dev.devfest.event.dto;

import dev.devfest.event.domain.GroupCode;

/** A generated team's sign-in, returned once by POST /admin/teams/generate; the password is never stored. */
public record TeamCredential(String teamName, GroupCode group, String email, String password) {}
