package dev.devfest.event.dto;

import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.RoomCode;
import java.time.Instant;

/** {@code signedIn}: an open session of the team account was seen in the last 2 minutes. */
public record TeamView(
  long id,
  String name,
  GroupCode groupCode,
  long accountId,
  String accountEmail,
  boolean active,
  boolean signedIn,
  Instant lastSeenAt,
  boolean disqualified,
  /** The current round's room exception (the admin sent the team elsewhere), or null. */
  RoomCode exceptionRoom
) {}
