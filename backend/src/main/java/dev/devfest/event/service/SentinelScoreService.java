package dev.devfest.event.service;

import dev.devfest.event.domain.RoomActivity;
import dev.devfest.event.domain.sentinel.SentinelContent;
import dev.devfest.event.repository.EventRoomRecord;
import dev.devfest.event.repository.EventRoomRepository;
import dev.devfest.event.repository.SentinelWorkRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one read-only entry point to a team's AI room score, for a later global ranking (rules §19.11). The event's
 * current ranking does not use it. Nothing here writes, and nothing is stored beyond the stage points the team
 * earned when it saved each stage.
 */
@Service
public class SentinelScoreService {

  /** {@code points}: the sum of the team's saved stages (an unsaved stage is 0); {@code maximum}: 100. */
  public record RoomScore(BigDecimal points, int maximum) {}

  private final EventRoomRepository rooms;
  private final SentinelWorkRepository work;

  public SentinelScoreService(EventRoomRepository rooms, SentinelWorkRepository work) {
    this.rooms = rooms;
    this.work = work;
  }

  /** The team's score in the room that runs the AI mission, or empty when no room does. 2 SQL statements. */
  @Transactional(readOnly = true)
  public Optional<RoomScore> roomScore(long teamId) {
    Optional<EventRoomRecord> room = rooms.findAll().stream().filter(candidate -> candidate.activity() == RoomActivity.AI_QUIZ).findFirst();
    return room.map(found -> new RoomScore(work.summary(teamId, found.id()).total(), SentinelContent.MAX_POINTS));
  }
}
