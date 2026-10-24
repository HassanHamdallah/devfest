package dev.devfest.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mockingDetails;

import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.RoomCode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * A key change re-checks a room's submissions with a fixed set of SQL statements: 1 read of the answers,
 * 1 bulk verdict update, 1 delete and 1 insert of ROOM clues — whatever the number of submissions.
 */
class EventClueRecheckStatementsTest extends EventIntegrationTest {

  @MockitoSpyBean
  private JdbcTemplate spiedJdbc;

  @Test
  void aKeyChangeUsesTheSameStatementsForOneOrManySubmissions() {
    loadContent();
    setRound(1);
    openRoom(RoomCode.AI);
    TestTeam first = team("A1", GroupCode.A);
    expectOk(submit(first, RoomCode.AI, UUID.randomUUID(), "o", "WHITE VAN"));
    closeRoom(RoomCode.AI);
    List<String> one = recheckStatements("BLACK VAN");

    openRoom(RoomCode.AI);
    for (int i = 2; i <= 4; i++) {
      TestTeam team = team("A" + i, GroupCode.A);
      for (int n = 0; n < 8; n++) expectOk(submit(team, RoomCode.AI, UUID.randomUUID(), "o", n % 2 == 0 ? "BLACK VAN" : "nope"));
    }
    closeRoom(RoomCode.AI);
    assertEquals(25, (int) db.queryForObject("SELECT count(*) FROM room_submission", Integer.class));
    List<String> many = recheckStatements("WHITE VAN");

    assertEquals(4, one.stream().distinct().count(), one.toString());
    assertEquals(one, many);
  }

  /** The SQL texts sent while saving a new key that touch verdicts or collected clues, in call order. */
  private List<String> recheckStatements(String newKey) {
    clearInvocations(spiedJdbc);
    expectOk(setClueKey(RoomCode.AI, newKey));
    return mockingDetails(spiedJdbc)
      .getInvocations()
      .stream()
      .map(Invocation::getArguments)
      .filter(args -> args.length > 0 && args[0] instanceof String)
      .map(args -> (String) args[0])
      .filter(sql -> sql.contains("submission_clue_verdict") || sql.contains("collected_clue"))
      .toList();
  }
}
