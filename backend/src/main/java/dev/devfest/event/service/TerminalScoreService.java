package dev.devfest.event.service;

import dev.devfest.event.domain.TerminalAnswerKeys;
import dev.devfest.event.domain.TerminalFormScoring;
import dev.devfest.event.domain.TerminalFormValues;
import dev.devfest.event.domain.TerminalScoreItem;
import dev.devfest.event.repository.EventRoomRecord;
import dev.devfest.event.repository.TerminalAnswerKeyRepository;
import dev.devfest.event.repository.TerminalFormOverrideRepository;
import dev.devfest.event.repository.TerminalFormRepository;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The read-only entry point to the Linux room's /15 scores (the same scoring the organizer dashboard shows,
 * {@link TerminalFormScoring}), for the event score. Nothing here writes and nothing is stored: each call computes
 * the scores from the stored forms, the current keys and the organizer's overrides.
 */
@Service
public class TerminalScoreService {

  private final TerminalFormRepository forms;
  private final TerminalAnswerKeyRepository keys;
  private final TerminalFormOverrideRepository overrides;

  public TerminalScoreService(TerminalFormRepository forms, TerminalAnswerKeyRepository keys, TerminalFormOverrideRepository overrides) {
    this.forms = forms;
    this.keys = keys;
    this.overrides = overrides;
  }

  /** Every active team's score (0..15) in the given Linux terminal room, by team id. 3 SQL statements. */
  @Transactional(readOnly = true)
  public Map<Long, Integer> roomTotals(EventRoomRecord room) {
    TerminalAnswerKeys answerKeys = keys.find(room.id()).map(TerminalAnswerKeyRepository.StoredKeys::keys).orElse(TerminalAnswerKeys.empty());
    Map<Long, Map<TerminalScoreItem, Boolean>> byTeam = overrides.forRoom(room.id());
    Map<Long, Integer> totals = new HashMap<>();
    for (TerminalFormRepository.DashboardRow row : forms.dashboardRows(room.id())) {
      Map<TerminalScoreItem, Boolean> teamOverrides = byTeam.getOrDefault(row.teamId(), new EnumMap<>(TerminalScoreItem.class));
      TerminalFormScoring.Score score = row.form() == null
        ? TerminalFormScoring.notStarted(teamOverrides)
        : TerminalFormScoring.score(answerKeys, row.form().values(), room.clueKey(), teamOverrides);
      totals.put(row.teamId(), score.total());
    }
    return totals;
  }
}
