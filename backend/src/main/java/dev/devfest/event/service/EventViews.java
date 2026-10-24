package dev.devfest.event.service;

import dev.devfest.event.domain.GroupCapacity;
import dev.devfest.event.domain.RoomCode;
import dev.devfest.event.dto.AdminAlert;
import dev.devfest.event.dto.ClueVerdictView;
import dev.devfest.event.dto.EventStateView;
import dev.devfest.event.dto.JudgementView;
import dev.devfest.event.dto.PenaltyView;
import dev.devfest.event.dto.Receipt;
import dev.devfest.event.dto.RoomContentView;
import dev.devfest.event.dto.RoundClock;
import dev.devfest.event.repository.ClueVerdictRecord;
import dev.devfest.event.repository.EventRoomRecord;
import dev.devfest.event.repository.EventStateRecord;
import dev.devfest.event.repository.GroupSizeRecord;
import dev.devfest.event.repository.PenaltyRecord;
import dev.devfest.event.repository.RoomJudgementRecord;
import dev.devfest.event.repository.RoomSubmissionRecord;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Record → response mappings shared by several event services. */
public final class EventViews {

  /** Frontend route of the admin teams and accounts page. */
  public static final String TEAMS_PAGE = "/event/admin/teams";

  private EventViews() {}

  public static RoundClock roundClock(EventStateRecord state, Instant serverNow) {
    return new RoundClock(state.currentRound(), state.roundStartedAt(), state.roundMinutes(), serverNow);
  }

  public static EventStateView eventState(EventStateRecord state, Instant serverNow) {
    return new EventStateView(
      state.currentRound(),
      state.roundStartedAt(),
      state.roundMinutes(),
      serverNow,
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
      recheckedSubmissions
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
