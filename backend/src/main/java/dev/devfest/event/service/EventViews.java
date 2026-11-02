package dev.devfest.event.service;

import dev.devfest.event.domain.EvidenceVisibility;
import dev.devfest.event.domain.GroupCapacity;
import dev.devfest.event.domain.RoomCode;
import dev.devfest.event.domain.RoundTimer;
import dev.devfest.event.dto.AdminAlert;
import dev.devfest.event.dto.ClueVerdictView;
import dev.devfest.event.dto.EventStateView;
import dev.devfest.event.dto.JudgementView;
import dev.devfest.event.dto.PenaltyView;
import dev.devfest.event.dto.Receipt;
import dev.devfest.event.dto.RoomContentView;
import dev.devfest.event.dto.RoomEvidence;
import dev.devfest.event.dto.RoundClock;
import dev.devfest.event.repository.ClueVerdictRecord;
import dev.devfest.event.repository.EventRoomRecord;
import dev.devfest.event.repository.EventStateRecord;
import dev.devfest.event.repository.EventTeamRecord;
import dev.devfest.event.repository.GroupSizeRecord;
import dev.devfest.event.repository.PenaltyRecord;
import dev.devfest.event.repository.RoomJudgementRecord;
import dev.devfest.event.repository.RoomSubmissionRecord;
import dev.devfest.event.repository.TeamRoomProgressRecord;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Record → response mappings shared by several event services. */
public final class EventViews {

  /** Frontend route of the admin teams and accounts page. */
  public static final String TEAMS_PAGE = "/event/admin/teams";

  private EventViews() {}

  public static RoundClock roundClock(EventStateRecord state, Instant serverNow) {
    Optional<RoundTimer> timer = state.timer();
    return new RoundClock(
      state.currentRound(),
      timer.map(t -> t.effectivePhase(serverNow)).orElse(null),
      timer.map(t -> t.remainingSeconds(serverNow)).orElse(null),
      timer.map(RoundTimer::durationSeconds).orElse(null),
      serverNow
    );
  }

  /**
   * The team's evidence, one entry per room in room order, for the team board and the final (same rules in both).
   * {@code exceptions}: round → room of every room exception the team had.
   */
  public static List<RoomEvidence> evidence(
    EventTeamRecord team,
    EventStateRecord state,
    List<TeamRoomProgressRecord> rooms,
    Map<Integer, RoomCode> exceptions,
    Instant now
  ) {
    var phase = roundClock(state, now).phase();
    return rooms
      .stream()
      .map(room -> {
        boolean finished = EvidenceVisibility.roomFinished(
          room.code(),
          team.groupCode(),
          exceptions,
          state.currentRound(),
          phase,
          state.finalEverOpened()
        );
        if (!finished) {
          return new RoomEvidence(room.code(), room.name(), false, null, null, null, room.clueCollected(), null);
        }
        return new RoomEvidence(
          room.code(),
          room.name(),
          true,
          room.lastTechnicalOutput(),
          room.lastClueAnswer(),
          room.lastEffectiveCorrect(),
          room.clueCollected(),
          EvidenceVisibility.clueVisible(finished, room.clueCollected()) ? room.collectedClueKey() : null
        );
      })
      .toList();
  }

  public static EventStateView eventState(EventStateRecord state, Instant serverNow) {
    RoundClock clock = roundClock(state, serverNow);
    return new EventStateView(
      state.currentRound(),
      clock.phase(),
      clock.remainingSeconds(),
      clock.durationSeconds(),
      serverNow,
      state.roundStartedAt(),
      state.roundMinutes(),
      state.finalOpen(),
      state.finalStartedAt(),
      state.finalClosedAt(),
      state.resultsPublished(),
      state.revision()
    );
  }

  /** Group balance warnings (D2) as alerts that link to the teams page. */
  public static List<AdminAlert> groupWarnings(int plannedTeams, List<GroupSizeRecord> groups) {
    return GroupCapacity.balanceWarnings(plannedTeams, GroupSizeRecord.asMap(groups))
      .stream()
      .map(warning -> new AdminAlert(warning.code(), warning.message(), TEAMS_PAGE))
      .toList();
  }

  public static Receipt receipt(RoomSubmissionRecord submission) {
    return receipt(
      submission.id(),
      submission.roomCode(),
      submission.receivedAt(),
      submission.clueCorrect(),
      submission.effectiveCorrect()
    );
  }

  /** The team sees only the effective result, and whether it changed since the submission. */
  public static Receipt receipt(UUID id, RoomCode roomCode, Instant receivedAt, boolean atSubmission, boolean effective) {
    return new Receipt(id, roomCode, receivedAt, effective, effective != atSubmission);
  }

  public static JudgementView judgement(RoomJudgementRecord judgement) {
    return new JudgementView(
      judgement.teamId(),
      judgement.roomId(),
      judgement.status(),
      judgement.hints(),
      judgement.note(),
      judgement.version(),
      judgement.reviewerName(),
      judgement.updatedAt()
    );
  }

  public static PenaltyView penalty(PenaltyRecord penalty) {
    return new PenaltyView(
      penalty.id(),
      penalty.teamId(),
      penalty.roomId(),
      penalty.kind(),
      penalty.minutes(),
      penalty.note(),
      penalty.createdByName(),
      penalty.createdAt(),
      penalty.voided(),
      penalty.voidedByName(),
      penalty.voidedAt(),
      penalty.voidReason()
    );
  }

  public static RoomContentView roomContent(EventRoomRecord room) {
    return roomContent(room, 0);
  }

  public static RoomContentView roomContent(EventRoomRecord room, int recheckedSubmissions) {
    return new RoomContentView(
      room.id(),
      room.code(),
      room.name(),
      room.clueKey(),
      room.recoveryPrompt(),
      room.recoveryAnswer(),
      room.version(),
      recheckedSubmissions,
      room.terminalEnabled()
    );
  }

  public static ClueVerdictView clueVerdict(ClueVerdictRecord verdict) {
    return new ClueVerdictView(
      verdict.submissionId(),
      verdict.teamId(),
      verdict.autoCorrect(),
      verdict.override(),
      verdict.effectiveCorrect(),
      verdict.version(),
      verdict.overrideByName(),
      verdict.overrideAt()
    );
  }
}
