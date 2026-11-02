package dev.devfest.event.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GroupCapacityTest {

  @Test
  void maxPerGroupRoundsUp() {
    assertEquals(1, GroupCapacity.maxPerGroup(1));
    assertEquals(1, GroupCapacity.maxPerGroup(4));
    assertEquals(2, GroupCapacity.maxPerGroup(5));
    assertEquals(4, GroupCapacity.maxPerGroup(16));
    assertEquals(5, GroupCapacity.maxPerGroup(20));
    assertEquals(16, GroupCapacity.maxPerGroup(64));
    assertThrows(EventRuleException.class, () -> GroupCapacity.maxPerGroup(0));
    assertThrows(EventRuleException.class, () -> GroupCapacity.maxPerGroup(65));
  }

  @Test
  void aBalancedFullEventHasNoWarnings() {
    assertEquals(List.of(), GroupCapacity.balanceWarnings(16, Map.of(GroupCode.A, 4, GroupCode.B, 4, GroupCode.C, 4, GroupCode.D, 4)));
    assertEquals(List.of(), GroupCapacity.balanceWarnings(10, Map.of(GroupCode.A, 3, GroupCode.B, 3, GroupCode.C, 2, GroupCode.D, 2)));
  }

  @Test
  void countMismatchIsReported() {
    List<GroupCapacity.Warning> warnings = GroupCapacity.balanceWarnings(16, Map.of(GroupCode.A, 4, GroupCode.B, 4, GroupCode.C, 4, GroupCode.D, 3));
    assertEquals(List.of(new GroupCapacity.Warning("TEAM_COUNT", "There are 15 active teams; the planned count is 16.")), warnings);
  }

  @Test
  void overfullAndUnbalancedGroupsAreReported() {
    List<GroupCapacity.Warning> warnings = GroupCapacity.balanceWarnings(8, Map.of(GroupCode.A, 3, GroupCode.B, 1, GroupCode.C, 2, GroupCode.D, 2));
    assertEquals(
      List.of(
        new GroupCapacity.Warning("GROUP_OVER_CAPACITY", "Group A has 3 active teams (max 2)."),
        new GroupCapacity.Warning("GROUP_UNBALANCED", "Group sizes differ by more than 1 (A 3, B 1, C 2, D 2).")
      ),
      warnings
    );
  }

  @Test
  void missingGroupsCountAsEmpty() {
    List<String> codes = GroupCapacity.balanceWarnings(4, Map.of(GroupCode.A, 2)).stream().map(GroupCapacity.Warning::code).toList();
    assertEquals(List.of("TEAM_COUNT", "GROUP_OVER_CAPACITY", "GROUP_UNBALANCED"), codes);
  }
}
