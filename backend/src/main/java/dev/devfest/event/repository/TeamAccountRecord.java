package dev.devfest.event.repository;

import dev.devfest.event.domain.GroupCode;

/** A team with the email of its member account, for organization screens. */
public record TeamAccountRecord(
  long id,
  String name,
  GroupCode groupCode,
  long accountId,
  String accountEmail,
  boolean active
) {}
