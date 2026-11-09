package dev.devfest.event.service;

import dev.devfest.event.domain.ContestScoring;
import dev.devfest.event.domain.RoomActivity;
import dev.devfest.event.repository.ContestRepository;
import dev.devfest.event.repository.ContestTeamRecord;
import dev.devfest.event.repository.EventRoomRecord;
import dev.devfest.event.repository.EventRoomRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one read-only entry point to a team's Problem Solving score, for a later global ranking (rules §20.4). The
 * event's current ranking does not use it. Nothing here writes, and nothing is stored: the score is computed from the
 * typed results on every call.
 */
@Service
public class ContestScoreService {

  /** {@code points}: problem points plus the bonus; {@code maximum}: the most any team can earn in the room (15). */
  public record RoomScore(int points, int maximum) {}

  private final EventRoomRepository rooms;
  private final ContestRepository contest;

  public ContestScoreService(EventRoomRepository rooms, ContestRepository contest) {
    this.rooms = rooms;
    this.contest = contest;
  }

  /** Every scored team's total (0..15) in the given contest room, by team id. One statement whatever the number of teams. */
  @Transactional(readOnly = true)
  public java.util.Map<Long, Integer> roomTotals(EventRoomRecord room) {
    java.util.Map<Long, Integer> totals = new java.util.HashMap<>();
    ContestBoard.scores(contest.teams(room.id()), room.position()).forEach((teamId, score) -> totals.put(teamId, score.total()));
    return totals;
  }

  /**
   * The team's score in the room that runs the contest activity, or empty when no room does, the team is inactive or
   * disqualified (those teams are left out of the placement), or no result has been saved for it yet. 3 SQL statements.
   */
  @Transactional(readOnly = true)
  public Optional<RoomScore> roomScore(long teamId) {
    Optional<EventRoomRecord> room = rooms.findAll().stream().filter(candidate -> candidate.activity() == RoomActivity.CONTEST_RESULTS).findFirst();
    if (room.isEmpty()) return Optional.empty();
    List<ContestTeamRecord> teams = contest.teams(room.get().id());
    return Optional
      .ofNullable(ContestBoard.scores(teams, room.get().position()).get(teamId))
      .map(score -> new RoomScore(score.total(), ContestScoring.MAX_TOTAL));
  }
}
