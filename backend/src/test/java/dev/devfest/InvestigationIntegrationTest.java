package dev.devfest;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.devfest.api.Errors.ApiException;
import dev.devfest.auth.AuthService;
import dev.devfest.investigation.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
  properties = {
    "app.worker-enabled=false",
    "app.seed=false",
    "app.bootstrap-email=",
    "app.jwt-secret=devfest-test-only-secret-with-48-characters-123456",
    "spring.config.import=",
  }
)
@AutoConfigureMockMvc
class InvestigationIntegrationTest {

  @Autowired
  JdbcTemplate db;

  @Autowired
  AuthService auth;

  @Autowired
  InvestigationOrganization organization;

  @Autowired
  InvestigationChallenges challenges;

  @Autowired
  InvestigationSubmissions submissions;

  @Autowired
  InvestigationScoring scoring;

  @Autowired
  InvestigationLifecycle lifecycle;

  @Autowired
  PasswordEncoder passwords;

  @Autowired
  MockMvc mvc;

  @Autowired
  ObjectMapper json;

  AuthService.Principal admin, manager, otherManager, member, otherMember;
  long room, otherRoom, team, otherTeam, challenge, finalChallenge;
  static String passwordHash;
  final String password = "Investigation-test-only-123!";
  final InvestigationSubmissions.Report report =
    new InvestigationSubmissions.Report("Conclusion", "Reasoning", "Evidence");

  @BeforeEach
  void setup() {
    assertEquals(
      "devfest_test",
      db.queryForObject("SELECT current_database()", String.class)
    );
    db.execute(
      "TRUNCATE app_user,room,investigation_challenge RESTART IDENTITY CASCADE"
    );
    db.update(
      "UPDATE investigation_settings SET rules_confirmed=false,results_published=false"
    );
    if (passwordHash == null) passwordHash = passwords.encode(password);
    admin = account("admin", "ADMIN");
    manager = account("manager", "MANAGER");
    otherManager = account("othermanager", "MANAGER");
    member = account("member", "MEMBER");
    otherMember = account("othermember", "MEMBER");
    room = organization.room(
      admin,
      null,
      new InvestigationOrganization.RoomEdit("First room", false)
    );
    otherRoom = organization.room(
      admin,
      null,
      new InvestigationOrganization.RoomEdit("Second room", false)
    );
    organization.managers(
      admin,
      room,
      new InvestigationOrganization.Assignment(List.of(manager.id()))
    );
    organization.managers(
      admin,
      otherRoom,
      new InvestigationOrganization.Assignment(List.of(otherManager.id()))
    );
    team = organization.team(
      admin,
      null,
      new InvestigationOrganization.TeamEdit(
        "First team",
        room,
        member.id(),
        false
      )
    );
    otherTeam = organization.team(
      admin,
      null,
      new InvestigationOrganization.TeamEdit(
        "Second team",
        otherRoom,
        otherMember.id(),
        false
      )
    );
    challenge = challenges
      .save(
        manager,
        room,
        null,
        edit(
          InvestigationChallenges.Kind.ANSWER,
          InvestigationChallenges.State.OPEN,
          0
        )
      )
      .id();
    finalChallenge = challenges
      .save(
        manager,
        room,
        null,
        edit(
          InvestigationChallenges.Kind.FINAL,
          InvestigationChallenges.State.OPEN,
          0
        )
      )
      .id();
    scoring.confirm(admin);
  }

  AuthService.Principal account(String name, String role) {
    long id = db.queryForObject(
      "INSERT INTO app_user(email,name,password_hash,role) VALUES(?,?,?,?) RETURNING id",
      Long.class,
      name + "@test.local",
      name,
      passwordHash,
      role
    );
    return new AuthService.Principal(id, role, UUID.randomUUID());
  }

  String token(AuthService.Principal p) {
    return auth.login(auth.user(p.id()).email(), password).accessToken();
  }

  InvestigationChallenges.Edit edit(
    InvestigationChallenges.Kind kind,
    InvestigationChallenges.State state,
    long version
  ) {
    return new InvestigationChallenges.Edit(
      "Investigation " + kind,
      "PRIVATE_INSTRUCTIONS",
      List.of("https://example.org/evidence"),
      "Text",
      "Reasoned evidence, up to 100 points",
      100,
      kind,
      state,
      Instant.now().minusSeconds(3600),
      Instant.now().plusSeconds(3600),
      version
    );
  }

  UUID submit() {
    return submissions
      .submit(member, challenge, UUID.randomUUID(), report)
      .id();
  }

  @Test
  void directApiIsolationAndRoleBoundaries() throws Exception {
    mvc
      .perform(get("/api/v1/investigation/rooms"))
      .andExpect(status().isUnauthorized());
    mvc
      .perform(
        get("/api/v1/investigation/rooms/" + otherRoom + "/challenges").header(
          "Authorization",
          "Bearer " + token(manager)
        )
      )
      .andExpect(status().isNotFound());
    mvc
      .perform(
        get("/api/v1/investigation/rooms/" + otherRoom + "/submissions").header(
          "Authorization",
          "Bearer " + token(member)
        )
      )
      .andExpect(status().isNotFound());
    mvc
      .perform(
        get("/api/v1/investigation/accounts").header(
          "Authorization",
          "Bearer " + token(manager)
        )
      )
      .andExpect(status().isForbidden());
    mvc
      .perform(
        get("/api/v1/admin/participants").header(
          "Authorization",
          "Bearer " + token(manager)
        )
      )
      .andExpect(status().isForbidden());
    mvc
      .perform(
        get("/api/v1/scoreboard").header(
          "Authorization",
          "Bearer " + token(member)
        )
      )
      .andExpect(status().isForbidden());
    var id = submit();
    mvc
      .perform(
        get("/api/v1/investigation/submissions/" + id).header(
          "Authorization",
          "Bearer " + token(otherMember)
        )
      )
      .andExpect(status().isNotFound());
    mvc
      .perform(
        put("/api/v1/investigation/submissions/" + id + "/score")
          .header("Authorization", "Bearer " + token(member))
          .contentType("application/json")
          .content("{\"points\":100,\"feedback\":\"\",\"version\":0}")
      )
      .andExpect(status().isNotFound());
    assertEquals(1, organization.rooms(manager).size());
    assertEquals(1, organization.teams(member).size());
  }

  @Test
  void draftAndFutureChallengeContentNeverReachMemberResponses()
    throws Exception {
    db.update(
      "UPDATE investigation_challenge SET state='DRAFT' WHERE id=?",
      challenge
    );
    db.update(
      "UPDATE investigation_challenge SET opens_at=now()+interval '10 minutes' WHERE id=?",
      finalChallenge
    );
    mvc
      .perform(
        get("/api/v1/investigation/rooms/" + room + "/challenges").header(
          "Authorization",
          "Bearer " + token(member)
        )
      )
      .andExpect(status().isOk())
      .andExpect(content().json("[]"));
    assertEquals(
      404,
      assertThrows(ApiException.class, () ->
        submissions.draft(member, challenge)
      ).status
    );
    assertEquals(404, assertThrows(ApiException.class, () -> submit()).status);
    assertEquals(2, challenges.list(manager, room).size());
  }

  @Test
  void enrollmentConstraintsPreflightAndArchivePreserveWork() {
    assertThrows(
      org.springframework.dao.DataIntegrityViolationException.class,
      () ->
        organization.team(
          admin,
          null,
          new InvestigationOrganization.TeamEdit(
            "Duplicate rep",
            room,
            member.id(),
            false
          )
        )
    );
    assertEquals(
      400,
      assertThrows(ApiException.class, () ->
        organization.team(
          admin,
          null,
          new InvestigationOrganization.TeamEdit(
            "Wrong role",
            room,
            manager.id(),
            false
          )
        )
      ).status
    );
    var id = submit();
    assertEquals(
      409,
      assertThrows(ApiException.class, () ->
        organization.team(
          admin,
          team,
          new InvestigationOrganization.TeamEdit(
            "First team",
            otherRoom,
            member.id(),
            false
          )
        )
      ).status
    );
    organization.team(
      admin,
      team,
      new InvestigationOrganization.TeamEdit(
        "First team",
        room,
        member.id(),
        true
      )
    );
    assertEquals(id, submissions.get(member, id).id());
    assertEquals(404, assertThrows(ApiException.class, () -> submit()).status);
    organization.managers(
      admin,
      room,
      new InvestigationOrganization.Assignment(List.of())
    );
    assertTrue(
      organization
        .preflight(admin)
        .stream()
        .anyMatch(s -> s.contains("no active manager"))
    );
    assertEquals(
      404,
      assertThrows(ApiException.class, () ->
        challenges.list(manager, room)
      ).status
    );
  }

  @Test
  void concurrentRetriesReceiptAndPayloadConflict() throws Exception {
    UUID key = UUID.randomUUID();
    try (var pool = Executors.newFixedThreadPool(8)) {
      var jobs = new ArrayList<Future<InvestigationSubmissions.Receipt>>();
      for (int i = 0; i < 8; i++) jobs.add(
        pool.submit(() -> submissions.submit(member, challenge, key, report))
      );
      var receipt = jobs.getFirst().get();
      for (var job : jobs) assertEquals(receipt, job.get());
      assertEquals(
        1,
        db.queryForObject(
          "SELECT count(*) FROM investigation_submission",
          Integer.class
        )
      );
      assertEquals(
        receipt.receivedAt(),
        submissions.get(member, receipt.id()).receivedAt()
      );
    }
    assertEquals(
      409,
      assertThrows(ApiException.class, () ->
        submissions.submit(
          member,
          challenge,
          key,
          new InvestigationSubmissions.Report("different", "", "")
        )
      ).status
    );
  }

  @Test
  void deadlineAndArchivedRoomAllowOnlyPreviouslyCommittedRetries() {
    UUID key = UUID.randomUUID();
    var receipt = submissions.submit(member, challenge, key, report);
    db.update(
      "UPDATE investigation_challenge SET closes_at=now()-interval '1 second' WHERE id=?",
      challenge
    );
    assertEquals(receipt, submissions.submit(member, challenge, key, report));
    assertEquals(409, assertThrows(ApiException.class, () -> submit()).status);
    organization.room(
      admin,
      room,
      new InvestigationOrganization.RoomEdit("First room", true)
    );
    assertEquals(receipt, submissions.submit(member, challenge, key, report));
    assertEquals(receipt.id(), submissions.get(member, receipt.id()).id());
    var c = challenges.get(challenge, false);
    assertDoesNotThrow(() ->
      InvestigationChallenges.requireOpen(c, c.opensAt())
    );
    assertThrows(ApiException.class, () ->
      InvestigationChallenges.requireOpen(c, c.closesAt())
    );
  }

  @Test
  void draftsUseVersionsFinalReportLocksAndNeverOverwrites() throws Exception {
    var draft = submissions.saveDraft(
      member,
      finalChallenge,
      new InvestigationSubmissions.DraftEdit(report, 0)
    );
    assertEquals(1, draft.version());
    assertEquals(report, submissions.draft(member, finalChallenge).report());
    assertEquals(
      409,
      assertThrows(ApiException.class, () ->
        submissions.saveDraft(
          member,
          finalChallenge,
          new InvestigationSubmissions.DraftEdit(report, 0)
        )
      ).status
    );
    assertEquals(
      404,
      assertThrows(ApiException.class, () ->
        submissions.draft(otherMember, finalChallenge)
      ).status
    );
    try (var pool = Executors.newFixedThreadPool(2)) {
      var jobs = new ArrayList<Future<Boolean>>();
      for (int i = 0; i < 2; i++) jobs.add(
        pool.submit(() -> {
          try {
            submissions.submit(
              member,
              finalChallenge,
              UUID.randomUUID(),
              report
            );
            return true;
          } catch (ApiException e) {
            assertEquals(409, e.status);
            return false;
          }
        })
      );
      assertNotEquals(jobs.get(0).get(), jobs.get(1).get());
    }
    assertTrue(submissions.draft(member, finalChallenge).locked());
    assertEquals(
      409,
      assertThrows(ApiException.class, () ->
        submissions.saveDraft(
          member,
          finalChallenge,
          new InvestigationSubmissions.DraftEdit(report, 1)
        )
      ).status
    );
    assertEquals(
      1,
      db.queryForObject(
        "SELECT count(*) FROM investigation_submission",
        Integer.class
      )
    );
  }

  @Test
  void scoringEditsRetriesPublicationAndLatestAnswerTotals() {
    var id = submit();
    var score = new InvestigationScoring.ScoreEdit(70, "Good evidence", 0);
    scoring.score(manager, id, score);
    scoring.score(manager, id, score);
    assertEquals(1, scoring.history(manager, id).size());
    assertEquals(70, scoring.results(manager, room).getFirst().total());
    assertNull(submissions.get(member, id).points());
    assertEquals(
      403,
      assertThrows(ApiException.class, () ->
        scoring.results(member, room)
      ).status
    );
    assertEquals(
      404,
      assertThrows(ApiException.class, () ->
        scoring.score(otherManager, id, score)
      ).status
    );
    assertEquals(
      400,
      assertThrows(ApiException.class, () ->
        scoring.score(
          manager,
          id,
          new InvestigationScoring.ScoreEdit(101, "", 1)
        )
      ).status
    );
    scoring.publish(admin, true);
    assertEquals(70, submissions.get(member, id).points());
    scoring.score(
      manager,
      id,
      new InvestigationScoring.ScoreEdit(80, "Revised", 1)
    );
    assertFalse(scoring.settings().resultsPublished());
    assertEquals(80, scoring.results(manager, room).getFirst().total());
    assertEquals(
      409,
      assertThrows(ApiException.class, () ->
        scoring.score(
          manager,
          id,
          new InvestigationScoring.ScoreEdit(90, "stale", 1)
        )
      ).status
    );
    var newest = submit();
    assertEquals(0, scoring.results(manager, room).getFirst().total());
    assertEquals(1, scoring.results(manager, room).getFirst().pending());
    scoring.score(
      manager,
      newest,
      new InvestigationScoring.ScoreEdit(50, "", 0)
    );
    assertEquals(50, scoring.results(manager, room).getFirst().total());
    assertEquals(2, submissions.list(member, room, 0).size());
    assertEquals(2, scoring.history(manager, id).size());
  }

  @Test
  void roomResultsUseSharedCompetitionRanks() {
    var extra = account("third", "MEMBER");
    organization.team(
      admin,
      null,
      new InvestigationOrganization.TeamEdit(
        "Third team",
        room,
        extra.id(),
        false
      )
    );
    var results = scoring.results(manager, room);
    assertEquals(2, results.size());
    assertEquals(1, results.get(0).rank());
    assertEquals(1, results.get(1).rank());
    assertEquals(1, scoring.results(otherManager, otherRoom).size());
  }

  @Test
  void publishedScoresAreNotLeakedThroughHistoryAndExportsAreAdminOnly()
    throws Exception {
    var id = submit();
    scoring.score(
      manager,
      id,
      new InvestigationScoring.ScoreEdit(37, "PRIVATE_FEEDBACK", 0)
    );
    String memberToken = token(member);
    mvc
      .perform(
        get("/api/v1/investigation/submissions/" + id + "/scores").header(
          "Authorization",
          "Bearer " + memberToken
        )
      )
      .andExpect(status().isNotFound());
    mvc
      .perform(
        get("/api/v1/investigation/rooms/" + room + "/submissions").header(
          "Authorization",
          "Bearer " + memberToken
        )
      )
      .andExpect(status().isOk())
      .andExpect(
        content().string(
          org.hamcrest.Matchers.not(
            org.hamcrest.Matchers.containsString("PRIVATE_FEEDBACK")
          )
        )
      );
    mvc
      .perform(
        get("/api/v1/investigation/export").header(
          "Authorization",
          "Bearer " + token(manager)
        )
      )
      .andExpect(status().isForbidden());
    String export = mvc
      .perform(
        get("/api/v1/investigation/export").header(
          "Authorization",
          "Bearer " + token(admin)
        )
      )
      .andExpect(status().isOk())
      .andReturn()
      .getResponse()
      .getContentAsString();
    assertFalse(export.contains("password_hash"));
    assertFalse(export.contains("refresh_token"));
    assertTrue(export.contains(id.toString()));
    mvc
      .perform(get("/api/v1/health"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.status").value("UP"));
  }

  @Test
  void resetDeactivationAndExpiredSessionsRejectDirectRequests()
    throws Exception {
    String old = token(member);
    organization.password(admin, member.id(), "Replacement-test-only-123!");
    mvc
      .perform(
        get("/api/v1/investigation/rooms").header(
          "Authorization",
          "Bearer " + old
        )
      )
      .andExpect(status().isUnauthorized());
    String current = auth
      .login(auth.user(member.id()).email(), "Replacement-test-only-123!")
      .accessToken();
    db.update(
      "UPDATE auth_session SET expires_at=now()-interval '1 second' WHERE user_id=?",
      member.id()
    );
    mvc
      .perform(
        get("/api/v1/investigation/rooms").header(
          "Authorization",
          "Bearer " + current
        )
      )
      .andExpect(status().isUnauthorized());
    var managerToken = token(manager);
    organization.active(admin, manager.id(), false);
    mvc
      .perform(
        get("/api/v1/investigation/rooms").header(
          "Authorization",
          "Bearer " + managerToken
        )
      )
      .andExpect(status().isUnauthorized());
  }

  @Test
  void contentLocksAfterSavedWorkAndDatabaseScoreLimitHolds() {
    var id = submit();
    var old = challenges.get(challenge, false);
    var changed = new InvestigationChallenges.Edit(
      "Changed",
      old.instructions(),
      old.evidenceLinks(),
      old.answerFormat(),
      old.rubric(),
      old.maxScore(),
      old.kind(),
      old.state(),
      old.opensAt(),
      old.closesAt(),
      old.version()
    );
    assertEquals(
      409,
      assertThrows(ApiException.class, () ->
        challenges.save(manager, room, challenge, changed)
      ).status
    );
    assertThrows(
      org.springframework.dao.DataIntegrityViolationException.class,
      () ->
        db.update(
          "INSERT INTO investigation_score(submission_id,points,feedback,reviewer_id,updated_at) VALUES(?,101,'',?,now())",
          id,
          manager.id()
        )
    );
    assertTrue(
      submissions
        .get(member, id)
        .challengeSnapshot()
        .contains("PRIVATE_INSTRUCTIONS")
    );
  }

  @Test
  void unconfirmedRulesAndInvalidEvidenceAreRejected() {
    db.update("UPDATE investigation_settings SET rules_confirmed=false");
    assertEquals(409, assertThrows(ApiException.class, () -> submit()).status);
    var old = edit(
      InvestigationChallenges.Kind.ANSWER,
      InvestigationChallenges.State.OPEN,
      0
    );
    var bad = new InvestigationChallenges.Edit(
      old.title(),
      old.instructions(),
      List.of("javascript:alert(1)"),
      old.answerFormat(),
      old.rubric(),
      old.maxScore(),
      old.kind(),
      old.state(),
      old.opensAt(),
      old.closesAt(),
      0
    );
    assertEquals(
      400,
      assertThrows(ApiException.class, () ->
        challenges.save(manager, room, null, bad)
      ).status
    );
  }

  @Test
  void scheduledLifecycleEventsAreRecordedOnce() {
    lifecycle.observe();
    lifecycle.observe();
    assertEquals(
      2,
      db.queryForObject(
        "SELECT count(*) FROM audit_event WHERE action='INV_LIFECYCLE_OPEN'",
        Integer.class
      )
    );
    db.update(
      "UPDATE investigation_challenge SET closes_at=now()-interval '1 second' WHERE id=?",
      challenge
    );
    lifecycle.observe();
    lifecycle.observe();
    assertEquals(
      1,
      db.queryForObject(
        "SELECT count(*) FROM audit_event WHERE action='INV_LIFECYCLE_CLOSED'",
        Integer.class
      )
    );
  }
}
