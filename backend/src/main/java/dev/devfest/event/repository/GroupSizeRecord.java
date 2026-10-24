package dev.devfest.event.repository;

import dev.devfest.event.domain.GroupCode;

/** Number of active teams in a group. */
public record GroupSizeRecord(GroupCode groupCode, int activeTeams) {}
