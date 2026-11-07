package dev.devfest.event.service;

import dev.devfest.event.domain.ContestProblem;
import dev.devfest.event.domain.ContestScoring;
import dev.devfest.event.domain.Rotation;
import dev.devfest.event.dto.ContestResultsView.RoundView;
import dev.devfest.event.dto.ContestResultsView.TeamRow;
import dev.devfest.event.repository.ContestTeamRecord;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Turns the contest room's stored rows into scored rounds (rules §20.4). Placement is computed inside one round, i.e.
 * among the teams of the one group that visits the room in that round, so a team is never compared with a team of
 * another round. Pure; the caller supplies the rows.
 */
final class ContestBoard {

  private ContestBoard() {}

  /** The round in which the team's group visits the room at {@code roomPosition}. */
  static int roundOf(ContestTeamRecord team, int roomPosition) {
    return Rotation.roundFor(team.groupCode().index(), roomPosition);
  }

  /** The score of every team that has a saved result, by team id. */
  static Map<Long, ContestScoring.Score> scores(List<ContestTeamRecord> teams, int roomPosition) {
    Map<Integer, List<ContestScoring.Result>> byRound = new HashMap<>();
    for (ContestTeamRecord team : teams) {
      if (!team.saved()) continue;
      byRound
        .computeIfAbsent(roundOf(team, roomPosition), round -> new ArrayList<>())
        .add(new ContestScoring.Result(team.teamId(), team.solved(), team.penaltyMinutes()));
    }
    Map<Long, ContestScoring.Score> scores = new HashMap<>();
    byRound.values().forEach(round -> scores.putAll(ContestScoring.scoreRound(round)));
    return scores;
  }

  /** Rounds 1..4 in order, each with its teams sorted by place (teams without a result last, by name). */
  static List<RoundView> rounds(List<ContestTeamRecord> teams, int roomPosition) {
    Map<Long, ContestScoring.Score> scores = scores(teams, roomPosition);
    Map<Integer, List<ContestTeamRecord>> byRound = teams.stream().collect(Collectors.groupingBy(team -> roundOf(team, roomPosition)));
    List<RoundView> rounds = new ArrayList<>();
    for (int roundNumber = Rotation.FIRST_ROUND; roundNumber <= Rotation.LAST_ROUND; roundNumber++) {
      int round = roundNumber;
      List<ContestTeamRecord> inRound = byRound.getOrDefault(round, List.of());
      if (inRound.isEmpty()) continue;
      List<TeamRow> rows = inRound
        .stream()
        .map(team -> row(team, round, scores.get(team.teamId())))
        .sorted(
          Comparator
            .comparing(TeamRow::place, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(TeamRow::teamName)
        )
        .toList();
      rounds.add(
        new RoundView(
          round,
          inRound.get(0).groupCode(),
          rows.size(),
          (int) rows.stream().filter(TeamRow::saved).count(),
          (int) rows.stream().filter(TeamRow::eligible).count(),
          (int) rows.stream().filter(TeamRow::released).count(),
          (int) rows.stream().filter(row -> row.eligible() && !row.released()).count(),
          rows
        )
      );
    }
    return rounds;
  }

  private static TeamRow row(ContestTeamRecord team, int round, ContestScoring.Score score) {
    boolean saved = score != null;
    return new TeamRow(
      team.teamId(),
      team.teamName(),
      team.groupCode(),
      round,
      saved,
      saved ? team.solved().stream().sorted().toList() : List.<ContestProblem>of(),
      saved ? team.penaltyMinutes() : 0,
      saved ? score.solvedCount() : 0,
      saved ? score.problemPoints() : 0,
      saved ? score.bonus() : 0,
      saved ? score.total() : 0,
      saved ? score.place() : null,
      saved && score.eligible(),
      team.released(),
      team.releasedAt(),
      team.released() && !(saved && score.eligible()),
      saved ? team.savedAt() : null
    );
  }
}
