package dev.devfest.event.service;

import static dev.devfest.admin.AdminController.csvCell;

import dev.devfest.audit.AuditLog;
import dev.devfest.auth.AuthService.Principal;
import dev.devfest.event.dto.TeamScoreBreakdown;
import dev.devfest.event.dto.TeamScoreBreakdown.RoomScoreLine;
import dev.devfest.event.repository.TeamFinalRepository;
import dev.devfest.event.repository.TeamFinalRepository.ReportRow;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** The organizer's results spreadsheet, in ranking order. */
@Service
public class EventExportService {

  static final String HEADER =
    "rank,team,group,status,aiRoom,problemSolvingRoom,linuxRoom,hardwareRoom,hintsUsed,cheatingRooms,roomsTotal,"
      + "cluesSolved,cluePoints,suspect,time,finalScore,total,finishedAt,evidence1,evidence2,evidence3,evidence4,explanation\r\n";

  private final EventAccessPolicy access;
  private final ScoreBreakdownService scores;
  private final AuditLog audit;
  private final TeamFinalRepository finals;

  public EventExportService(EventAccessPolicy access, ScoreBreakdownService scores, AuditLog audit, TeamFinalRepository finals) {
    this.access = access;
    this.scores = scores;
    this.audit = audit;
    this.finals = finals;
  }

  /** Team names go through csvCell, which neutralizes spreadsheet formulas. Room columns follow room position order. */
  public String exportCsv(Principal principal) {
    access.requireAdmin(principal);
    StringBuilder csv = new StringBuilder(HEADER);
    Map<Long, ReportRow> reports = finals.reports().stream().collect(Collectors.toMap(ReportRow::teamId, Function.identity()));
    for (ScoreBreakdownService.RankedBreakdown ranked : scores.ranking()) {
      TeamScoreBreakdown b = ranked.breakdown();
      csv.append(text(ranked.rank())).append(',').append(csvCell(b.teamName())).append(',').append(b.groupCode()).append(',');
      csv.append(b.finalScore().status()).append(',');
      for (RoomScoreLine room : b.rooms()) csv.append(room.score().toPlainString()).append(',');
      csv.append(b.rooms().stream().mapToInt(RoomScoreLine::hints).sum()).append(',');
      csv.append(b.rooms().stream().filter(RoomScoreLine::cheating).count()).append(',');
      csv.append(b.roomsTotal().toPlainString()).append(',');
      csv.append(b.finalScore().cluesSolved()).append(',');
      csv.append(b.finalScore().clues().toPlainString()).append(',');
      csv.append(b.finalScore().suspect().toPlainString()).append(',');
      csv.append(b.finalScore().time().toPlainString()).append(',');
      csv.append(b.finalScore().total().toPlainString()).append(',');
      csv.append(b.total().toPlainString()).append(',');
      csv.append(text(b.finalScore().finishedAt())).append(',');
      ReportRow report = reports.get(b.teamId());
      csv.append(cell(report == null ? null : report.report().evidence1())).append(',');
      csv.append(cell(report == null ? null : report.report().evidence2())).append(',');
      csv.append(cell(report == null ? null : report.report().evidence3())).append(',');
      csv.append(cell(report == null ? null : report.report().evidence4())).append(',');
      csv.append(cell(report == null ? null : report.report().explanation())).append("\r\n");
    }
    audit.record(principal.id(), "EVENT_EXPORT", "results");
    return csv.toString();
  }

  /** A free-text cell: always quoted (csvCell), so its new lines and commas stay inside the cell. */
  private static String cell(String value) {
    return csvCell(value == null ? "" : value);
  }

  private static String text(Object value) {
    return Objects.toString(value, "");
  }
}
