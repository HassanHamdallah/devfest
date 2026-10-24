package dev.devfest.event.domain;

import static dev.devfest.event.domain.RoomCode.AI;
import static dev.devfest.event.domain.RoomCode.HARDWARE;
import static dev.devfest.event.domain.RoomCode.LINUX;
import static dev.devfest.event.domain.RoomCode.PROBLEM_SOLVING;
import static dev.devfest.event.domain.RotationPreview.WarningKind.MISS;
import static dev.devfest.event.domain.RotationPreview.WarningKind.REPEAT;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.devfest.event.domain.RotationPreview.Visit;
import dev.devfest.event.domain.RotationPreview.Warning;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RotationPreviewTest {

  @Test
  void theFirstRemainingRoundDependsOnTheRoundAndPhase() {
    assertEquals(1, RotationPreview.firstRemainingRound(0, Optional.empty()));
    assertEquals(2, RotationPreview.firstRemainingRound(2, Optional.of(RoundPhase.ASSIGNED)));
    assertEquals(3, RotationPreview.firstRemainingRound(2, Optional.of(RoundPhase.RUNNING)));
    assertEquals(3, RotationPreview.firstRemainingRound(2, Optional.of(RoundPhase.TIME_UP)));
    assertEquals(5, RotationPreview.firstRemainingRound(4, Optional.of(RoundPhase.PAUSED)));
    assertEquals(5, RotationPreview.firstRemainingRound(5, Optional.empty()));
  }

  @Test
  void beforeTheEventAMoveHasNoWarnings() {
    RotationPreview preview = RotationPreview.of(Set.of(), GroupCode.B, 1);
    assertEquals(
      List.of(new Visit(1, PROBLEM_SOLVING), new Visit(2, LINUX), new Visit(3, HARDWARE), new Visit(4, AI)),
      preview.remaining()
    );
    assertEquals(List.of(), preview.warnings());
  }

  @Test
  void aMidEventMoveWarnsAboutMissedAndRepeatedRooms() {
    // Group A has done rounds 1 and 2 (AI, Problem solving); group B visits Hardware and AI in rounds 3 and 4.
    Set<RoomCode> visited = RotationPreview.visitedRooms(GroupCode.A, 3, Map.of(), Set.of());
    RotationPreview preview = RotationPreview.of(visited, GroupCode.B, 3);
    assertEquals(List.of(AI, PROBLEM_SOLVING), preview.visited());
    assertEquals(List.of(new Visit(3, HARDWARE), new Visit(4, AI)), preview.remaining());
    assertEquals(List.of(new Warning(REPEAT, AI), new Warning(MISS, LINUX)), preview.warnings());
  }

  @Test
  void anAssignedRoundCanStillChange() {
    Set<RoomCode> visited = RotationPreview.visitedRooms(GroupCode.A, 2, Map.of(), Set.of());
    RotationPreview preview = RotationPreview.of(visited, GroupCode.B, 2);
    assertEquals(List.of(new Visit(2, LINUX), new Visit(3, HARDWARE), new Visit(4, AI)), preview.remaining());
    assertEquals(List.of(new Warning(REPEAT, AI), new Warning(MISS, PROBLEM_SOLVING)), preview.warnings());
  }

  @Test
  void afterTheLastRoundNothingRemains() {
    Set<RoomCode> visited = RotationPreview.visitedRooms(GroupCode.C, 5, Map.of(), Set.of());
    RotationPreview preview = RotationPreview.of(visited, GroupCode.A, 5);
    assertEquals(List.of(), preview.remaining());
    assertEquals(List.of(AI, PROBLEM_SOLVING, LINUX, HARDWARE), preview.visited());
    assertEquals(List.of(), preview.warnings());
  }

  @Test
  void exceptionsAndSubmissionsCountAsVisited() {
    Set<RoomCode> visited = RotationPreview.visitedRooms(GroupCode.A, 3, Map.of(1, LINUX), Set.of(HARDWARE));
    assertEquals(Set.of(LINUX, PROBLEM_SOLVING, HARDWARE), visited);
  }
}
