package dev.devfest.event.service;

import dev.devfest.auth.AuthService.Principal;
import dev.devfest.event.domain.CryptoContent;
import dev.devfest.event.domain.FinalStage;
import dev.devfest.event.dto.FinalBoardRow;
import dev.devfest.event.dto.RankingRow;
import dev.devfest.event.dto.TeamScoreBreakdown;
import dev.devfest.event.repository.TeamFinalRepository;
import dev.devfest.event.repository.TeamFinalRepository.AdminCounts;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The admin's two score views: the overall scoreboard (rank and the full breakdown of every team) and the live final
 * table (status, stage, attempts, answers and the parts of the final score). Both read only, a fixed number of
 * statements whatever the number of teams.
 */
@Service
public class EventFinalBoardService {

  private final EventAccessPolicy access;
  private final ScoreBreakdownService scores;
  private final TeamFinalRepository finals;
  private final FinalContentReader content;

  public EventFinalBoardService(EventAccessPolicy access, ScoreBreakdownService scores, TeamFinalRepository finals, FinalContentReader content) {
    this.access = access;
    this.scores = scores;
    this.finals = finals;
    this.content = content;
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
    Optional<CryptoContent> file = content.content();
    return scores
      .all()
      .stream()
      .map(b -> row(b, counts.get(b.teamId()), file.orElse(null)))
      .toList();
  }

  private static FinalBoardRow row(TeamScoreBreakdown b, AdminCounts c, CryptoContent file) {
    TeamScoreBreakdown.FinalScoreLine f = b.finalScore();
    String suspect = c.suspectId() != null && file != null && c.suspectId() <= file.suspects().size() ? file.suspects().get(c.suspectId() - 1) : null;
    return new FinalBoardRow(
      b.teamId(),
      b.teamName(),
      b.groupCode(),
      b.disqualified(),
      f.status(),
      stage(f, c),
      c.clues(),
      f.layersCorrect(),
      c.unlockAttempts(),
      c.unlockWrong(),
      c.layerAttempts(),
      c.layerWrong(),
      c.recoveryAttempts(),
      c.verdictE03(),
      c.verdictE03Correct(),
      c.verdictE04(),
      c.verdictE04Correct(),
      suspect,
      c.suspectCorrect(),
      f.activeSeconds(),
      f.finishedAt(),
      c.finalisedCause(),
      f.layers(),
      f.verdicts(),
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
      case NOT_QUALIFIED -> "Not qualified";
      case RECOVERY -> "Recovery";
      case COMPLETED -> "SUBMITTED".equals(c.finalisedCause()) ? "Submitted" : "Time up";
      case IN_FINAL -> {
        if (f.layersCorrect() >= CryptoContent.LAYER_COUNT) yield "Suspects";
        if (c.caseStartedAt() != null) yield "Layer " + (f.layersCorrect() + 1);
        yield c.unlocked() ? "Mission" : "Unlock";
      }
    };
  }
}
