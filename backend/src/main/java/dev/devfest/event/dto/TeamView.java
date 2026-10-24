package dev.devfest.event.dto;

import dev.devfest.event.domain.GroupCode;

public record TeamView(long id, String name, GroupCode groupCode, long accountId, String accountEmail, boolean active) {}
