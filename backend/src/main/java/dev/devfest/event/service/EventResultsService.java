package dev.devfest.event.service;

import static dev.devfest.api.Errors.fail;

import dev.devfest.auth.AuthService.Principal;
import dev.devfest.event.dto.PublicRankingRow;
import dev.devfest.event.dto.ResultsView;
import dev.devfest.event.dto.TeamScoreBreakdown.RoomScoreLine;
import dev.devfest.event.repository.EventStateRecord;
import dev.devfest.event.repository.EventStateRepository;
import java.util.List;
import org.springframework.stereotype.Service;

/** The results page: ADMIN always; MEMBER only once ADMIN has published; MANAGER never. */
@Service
public class EventResultsService {

  private final EventAccessPolicy access;
  private final EventStateRepository states;
  private final ScoreBreakdownService scores;

  public EventResultsService(EventAccessPolicy access, EventStateRepository states, ScoreBreakdownService scores) {
    this.access = access;
    this.states = states;
    this.scores = scores;
  }

  /** A fixed number of SQL statements whatever the number of teams: event state, then the score breakdown reads. */
  public ResultsView results(Principal principal) {
    access.requireResultsViewer(principal);
    EventStateRecord state = states.state();
    boolean admin = EventAccessPolicy.ADMIN.equals(principal.role());
    if (!admin && !state.resultsPublished()) throw fail(403, "Results are not published yet.");
    return new ResultsView(state.resultsPublished(), publicRows(scores.ranking()));
  }

  private static List<PublicRankingRow> publicRows(List<ScoreBreakdownService.RankedBreakdown> rows) {
    return rows
      .stream()
      .map(row ->
        new PublicRankingRow(
          row.rank(),
          row.breakdown().teamName(),
          row.breakdown().groupCode(),
          row.breakdown().rooms().stream().map(RoomScoreLine::score).toList(),
          row.breakdown().finalScore().total(),
          row.breakdown().total(),
          row.breakdown().finalScore().status(),
          row.breakdown().disqualified()
        )
      )
      .toList();
  }
}
