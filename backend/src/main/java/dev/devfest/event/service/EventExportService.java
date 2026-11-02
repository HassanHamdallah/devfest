package dev.devfest.event.service;

import static dev.devfest.admin.AdminController.csvCell;

import dev.devfest.audit.AuditLog;
import dev.devfest.auth.AuthService.Principal;
import dev.devfest.event.repository.EventRankingRepository;
import dev.devfest.event.repository.RankedTeamRecord;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** The organizer's results spreadsheet, in ranking order (decision D12). */
@Service
public class EventExportService {

  static final String HEADER =
    "rank,team,group,clues,recoveryUsed,accusationAttempts,penaltyMinutes,penaltyEntries,finishedAt,adjustedSeconds\r\n";

  private final EventAccessPolicy access;
  private final EventRankingRepository ranking;
  private final AuditLog audit;

  public EventExportService(EventAccessPolicy access, EventRankingRepository ranking, AuditLog audit) {
    this.access = access;
    this.ranking = ranking;
    this.audit = audit;
  }

  /** 1 SQL statement (ranking). Team names go through csvCell, which neutralizes spreadsheet formulas. */
  public String exportCsv(Principal principal) {
    access.requireAdmin(principal);
    StringBuilder csv = new StringBuilder(HEADER);
    for (RankedTeamRecord row : ranking.ranking()) {
      csv
        .append(text(row.rank()))
        .append(',')
        .append(csvCell(row.teamName()))
        .append(',')
        .append(row.groupCode())
        .append(',')
        .append(row.clues())
        .append(',')
        .append(row.recoveryUsed())
        .append(',')
        .append(row.accusationAttempts())
        .append(',')
        .append(row.penaltyMinutes())
        .append(',')
        .append(row.penaltyEntries())
        .append(',')
        .append(text(row.finishedAt()))
        .append(',')
        .append(text(row.adjustedSeconds()))
        .append("\r\n");
    }
    audit.record(principal.id(), "EVENT_EXPORT", "results");
    return csv.toString();
  }

  private static String text(Object value) {
    return Objects.toString(value, "");
  }
}
