package dev.devfest.event.dto;

/** Where the caller's event home is: the managed room for a MANAGER, the team name for a MEMBER. */
public record EventHome(String role, Long roomId, String teamName) {}
