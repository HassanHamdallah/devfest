package dev.devfest.event.service;

import static dev.devfest.admin.AdminController.csvCell;

import dev.devfest.audit.AuditLog;
import dev.devfest.auth.AuthService.Principal;
import dev.devfest.event.dto.TeamScoreBreakdown;
import dev.devfest.event.dto.TeamScoreBreakdown.RoomScoreLine;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** The organizer's results spreadsheet, in ranking order. */
@Service
public class EventExportService {

  static final String HEADER =
    "rank,team,group,status,aiRoom,problemSolvingRoom,linuxRoom,hardwareRoom,hintsUsed,cheatingRooms,roomsTotal,"
      + "cluesSolved,cluePoints,suspect,time,finalScore,total,finishedAt\r\n";

  private final EventAccessPolicy access;
  private final ScoreBreakdownService scores;
  private final AuditLog audit;

  public EventExportService(EventAccessPolicy access, ScoreBreakdownService scores, AuditLog audit) {
    this.access = access;
    this.scores = scores;
    this.audit = audit;
  }

  /** Team names go through csvCell, which neutralizes spreadsheet formulas. Room columns follow room position order. */
  public String exportCsv(Principal principal) {
    access.requireAdmin(principal);
    StringBuilder csv = new StringBuilder(HEADER);
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
      csv.append(text(b.finalScore().finishedAt())).append("\r\n");
    }
    audit.record(principal.id(), "EVENT_EXPORT", "results");
    return csv.toString();
  }

  private static String text(Object value) {
    return Objects.toString(value, "");
  }
}
