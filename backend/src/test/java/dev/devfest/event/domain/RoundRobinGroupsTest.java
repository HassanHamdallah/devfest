package dev.devfest.event.domain;

import static dev.devfest.event.domain.GroupCode.A;
import static dev.devfest.event.domain.GroupCode.B;
import static dev.devfest.event.domain.GroupCode.C;
import static dev.devfest.event.domain.GroupCode.D;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RoundRobinGroupsTest {

  @Test
  void emptyGroupsFillInOrder() {
    assertEquals(Optional.of(List.of(A, B, C, D, A, B)), RoundRobinGroups.assign(Map.of(), 6, 2));
  }

  @Test
  void theSmallestGroupIsFilledFirst() {
    Map<GroupCode, Integer> start = Map.of(A, 1, B, 1, C, 0, D, 0);
    assertEquals(Optional.of(List.of(C, D, A, B, C, D)), RoundRobinGroups.assign(start, 6, 2));
    assertEquals(Optional.of(List.of(B, B, D)), RoundRobinGroups.assign(Map.of(A, 3, B, 1, C, 3, D, 2), 3, 3));
  }

  @Test
  void tiesGoToTheEarlierGroup() {
    assertEquals(Optional.of(List.of(B)), RoundRobinGroups.assign(Map.of(A, 2, B, 1, C, 1, D, 1), 1, 4));
  }

  @Test
  void exceedingTheCapCreatesNothing() {
    assertTrue(RoundRobinGroups.assign(Map.of(A, 2, B, 2, C, 2, D, 1), 2, 2).isEmpty());
    assertEquals(Optional.of(List.of(D)), RoundRobinGroups.assign(Map.of(A, 2, B, 2, C, 2, D, 1), 1, 2));
    assertEquals(Optional.of(List.of()), RoundRobinGroups.assign(Map.of(), 0, 1));
  }
}
