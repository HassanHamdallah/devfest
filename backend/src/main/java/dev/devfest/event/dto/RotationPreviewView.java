package dev.devfest.event.dto;

import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.RoomCode;
import java.util.List;

/** One team in a swap or move preview: rooms done, rooms of the remaining rounds, and warnings. */
public record RotationPreviewView(
  long teamId,
  String teamName,
  GroupCode fromGroup,
  GroupCode toGroup,
  List<RoomCode> visited,
  List<Visit> remaining,
  List<String> warnings
) {
  public record Visit(int round, RoomCode room) {}
}
