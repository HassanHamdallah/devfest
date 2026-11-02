package dev.devfest.event.dto;

import java.util.List;

/** All event content for the admin editor; {@code readOnly} once the final has been opened. */
public record AdminContent(
  String encryptedMessage,
  Long correctSuspectId,
  long version,
  List<SuspectView> suspects,
  List<RoomContentView> rooms,
  boolean readOnly
) {}
