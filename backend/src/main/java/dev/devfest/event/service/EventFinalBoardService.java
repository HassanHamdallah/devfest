package dev.devfest.event.service;

import dev.devfest.auth.AuthService.Principal;
import dev.devfest.event.domain.FinalPhase;
import dev.devfest.event.domain.ShipmentContent;
import dev.devfest.event.domain.FinalStage;
import dev.devfest.event.dto.FinalBoardRow;
import dev.devfest.event.dto.FinalReportView;
import dev.devfest.event.dto.RankingRow;
import dev.devfest.event.dto.TeamScoreBreakdown;
import dev.devfest.event.domain.recovery.RecoveryPath;
import dev.devfest.event.domain.recovery.RecoveryPath.Step;
import dev.devfest.event.repository.EventStateRecord;
import dev.devfest.event.repository.EventStateRepository;
import dev.devfest.event.repository.RecoveryRepository;
import dev.devfest.event.repository.RecoveryRepository.StageCount;
import dev.devfest.event.repository.TeamFinalRepository;
import dev.devfest.event.repository.TeamFinalRepository.AdminCounts;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The admin's two score views: the overall scoreboard (rank and the full breakdown of every team) and the live final
 * table (status, stage, attempts, clues solved, the suspect and the parts of the final score). Both read only, a fixed number of
 * statements whatever the number of teams.
 */
@Service
public class EventFinalBoardService {

  private final EventAccessPolicy access;
  private final ScoreBreakdownService scores;
  private final TeamFinalRepository finals;
  private final FinalContentReader content;
  private final EventStateRepository states;
  private final RecoveryRepository recoveries;
  private final EventClock clock;

  public EventFinalBoardService(
    EventAccessPolicy access,
    ScoreBreakdownService scores,
    TeamFinalRepository finals,
    FinalContentReader content,
    EventStateRepository states,
    RecoveryRepository recoveries,
    EventClock clock
  ) {
    this.access = access;
    this.scores = scores;
    this.finals = finals;
    this.content = content;
    this.states = states;
    this.recoveries = recoveries;
    this.clock = clock;
  }

  /** Every active team with its place; a disqualified team has no rank and comes last. */
  @Transactional(readOnly = true)
  public List<RankingRow> scoreboard(Principal principal) {
    access.requireAdmin(principal);
    return scores.ranking().stream().map(r -> new RankingRow(r.rank(), r.breakdown())).toList();
  }

  @Transactional(readOnly = true)
  public List<FinalBoardRow> finalTable(Principal principal) {
    access.requireAdmin(principal);
    Map<Long, AdminCounts> counts = finals.adminCounts().stream().collect(Collectors.toMap(AdminCounts::teamId, Function.identity()));
    Optional<ShipmentContent> file = content.content();
    EventStateRecord state = states.state();
    Instant now = clock.now();
    Map<Long, Set<Step>> passed = new HashMap<>();
    for (StageCount count : recoveries.stageCounts()) {
      if (count.passed()) passed.computeIfAbsent(count.teamId(), id -> new HashSet<>()).add(new Step(count.level(), count.stage()));
    }
    return scores
      .all()
      .stream()
      .map(b -> {
        AdminCounts c = counts.get(b.teamId());
        Integer start = RecoveryPath.caseStartSeconds(c.keysAtOpen(), c.recoveryFinishedAtSeconds());
        FinalPhase phase = start == null ? null : FinalPhase.of(c.allDecryptedAtSeconds());
        Optional<Step> current = c.recoveryFinishedAtSeconds() != null
          ? Optional.empty()
          : RecoveryPath.current(RecoveryPath.requiredLevels(c.keysAtOpen()), passed.getOrDefault(b.teamId(), Set.of()));
        return row(b, c, file.orElse(null), phase, current, RecoveryPath.cryptoWindow(start, c.allDecryptedAtSeconds()));
      })
      .toList();
  }

  /** Every team with the suspect and Final Investigation Report it submitted (organizers only); one content read, one query. */
  @Transactional(readOnly = true)
  public List<FinalReportView> finalReports(Principal principal) {
    access.requireAdmin(principal);
    Optional<ShipmentContent> file = content.content();
    return finals
      .reports()
      .stream()
      .map(r -> {
        boolean submitted = r.suspectId() != null;
        String name = !submitted || file.isEmpty() ? null : file.get().suspect(r.suspectId()).map(ShipmentContent.Suspect::name).orElse(null);
        return new FinalReportView(
          r.teamId(),
          r.teamName(),
          submitted,
          r.suspectId(),
          name,
          r.suspectCorrect(),
          r.submittedAt(),
          r.activeSeconds(),
          r.report().evidence1(),
          r.report().evidence2(),
          r.report().evidence3(),
          r.report().evidence4(),
          r.report().explanation()
        );
      })
      .toList();
  }

  private static FinalBoardRow row(
    TeamScoreBreakdown b,
    AdminCounts c,
    ShipmentContent file,
    FinalPhase phase,
    Optional<Step> recovery,
    RecoveryPath.CryptoWindow window
  ) {
    TeamScoreBreakdown.FinalScoreLine f = b.finalScore();
    String suspect = c.suspectId() == null
      ? null
      : file == null ? c.suspectId() : file.suspect(c.suspectId()).map(ShipmentContent.Suspect::name).orElse(c.suspectId());
    String stage = stage(f, c);
    return new FinalBoardRow(
      b.teamId(),
      b.teamName(),
      b.groupCode(),
      b.disqualified(),
      f.status(),
      stage,
      "Case".equals(stage) ? phase : null,
      c.clues(),
      f.cluesSolved(),
      c.unlockAttempts(),
      c.unlockWrong(),
      c.clueAttempts(),
      c.clueWrong(),
      c.recoveryChecks(),
      c.keysAtOpen(),
      RecoveryPath.pathCode(c.keysAtOpen()),
      recovery.map(Step::level).orElse(null),
      recovery.map(Step::stage).orElse(null),
      c.recoveryFinishedAtSeconds(),
      window == null ? null : window.fromSeconds(),
      window == null ? null : window.toSeconds(),
      suspect,
      c.suspectCorrect(),
      f.activeSeconds(),
      f.finishedAt(),
      c.finalisedCause(),
      f.clues(),
      f.suspect(),
      f.time(),
      f.total()
    );
  }

  /** A short label for the stage the team is on. */
  private static String stage(TeamScoreBreakdown.FinalScoreLine f, AdminCounts c) {
    FinalStage status = f.status();
    return switch (status) {
      case LOCKED -> "Not open";
      case RECOVERY -> "Recovery";
      case COMPLETED -> "SUBMITTED".equals(c.finalisedCause()) ? "Submitted" : "Time up";
      case IN_FINAL -> {
        if (c.caseStartedAt() != null) yield "Case";
        yield c.unlocked() ? "Mission" : "Unlock";
      }
    };
  }
}
