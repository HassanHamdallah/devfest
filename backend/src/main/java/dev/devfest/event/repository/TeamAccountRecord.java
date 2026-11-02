package dev.devfest.event.repository;

import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.RoomCode;
import java.time.Instant;

/** A team with its member account's email and presence, for organization screens. */
public record TeamAccountRecord(
  long id,
  String name,
  GroupCode groupCode,
  long accountId,
  String accountEmail,
  boolean active,
  Instant lastSeenAt,
  boolean signedIn,
  boolean disqualified,
  RoomCode exceptionRoom
) {}
