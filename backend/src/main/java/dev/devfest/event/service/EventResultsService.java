package dev.devfest.event.service;

import static dev.devfest.api.Errors.fail;

import dev.devfest.auth.AuthService.Principal;
import dev.devfest.event.dto.PublicRankingRow;
import dev.devfest.event.dto.ResultsView;
import dev.devfest.event.repository.EventRankingRepository;
import dev.devfest.event.repository.EventStateRecord;
import dev.devfest.event.repository.EventStateRepository;
import dev.devfest.event.repository.RankedTeamRecord;
import java.util.List;
import org.springframework.stereotype.Service;

/** The results page: ADMIN always; MEMBER only once ADMIN has published; MANAGER never. */
@Service
public class EventResultsService {

  private final EventAccessPolicy access;
  private final EventStateRepository states;
  private final EventRankingRepository ranking;

  public EventResultsService(EventAccessPolicy access, EventStateRepository states, EventRankingRepository ranking) {
    this.access = access;
    this.states = states;
    this.ranking = ranking;
  }

  /** 2 SQL statements: event state, ranking. */
  public ResultsView results(Principal principal) {
    access.requireResultsViewer(principal);
    EventStateRecord state = states.state();
    boolean admin = EventAccessPolicy.ADMIN.equals(principal.role());
    if (!admin && !state.resultsPublished()) throw fail(403, "Results are not published yet.");
    return new ResultsView(state.resultsPublished(), publicRows(ranking.ranking()));
  }

  private static List<PublicRankingRow> publicRows(List<RankedTeamRecord> rows) {
    return rows
      .stream()
      .map(row ->
        new PublicRankingRow(
          row.rank(),
          row.teamName(),
          row.groupCode(),
          row.clues(),
          row.penaltyMinutes(),
          row.finished(),
          row.adjustedSeconds(),
          row.disqualified()
        )
      )
      .toList();
  }
}
