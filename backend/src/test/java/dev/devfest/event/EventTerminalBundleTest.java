package dev.devfest.event;

import static dev.devfest.event.domain.TerminalTestPackages.files;
import static dev.devfest.event.domain.TerminalTestPackages.untar;
import static dev.devfest.event.domain.TerminalTestPackages.zip;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.RoomCode;
import dev.devfest.event.domain.TerminalTestPackages.TarMember;
import dev.devfest.event.repository.RoomTerminalBundleRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * A room's terminal server package (rules §18): upload by the room's manager or the admin, the edit lock, the
 * download under exactly the gates of the text files, the ETag that answers 304 without loading the archive.
 * Charlie (group C) is in the Linux room in round 1, Alpha (group A) in the AI room.
 */
class EventTerminalBundleTest extends EventIntegrationTest {

  private static final String LOG_TEXT = "10:02 OVERRIDE east door\n";
  private static final String READ_ONLY = "Event content is read-only after the final opens.";

  @MockitoSpyBean
  private RoomTerminalBundleRepository bundleRepository;

  private TestTeam alpha;
  private TestTeam charlie;
  private long linux;
  private String linuxManager;

  @BeforeEach
  void prepare() {
    alpha = team("Alpha", GroupCode.A);
    charlie = team("Charlie", GroupCode.C);
    linux = rooms.get(RoomCode.LINUX);
    linuxManager = managers.get(RoomCode.LINUX);
  }

  // ---- upload and what the manager sees ---------------------------------------------------------------

  @Test
  void theManagerUploadsAPackageAndSeesItsSummaryButNeverTheArchive() throws Exception {
    long before = revision();
    Response saved = expectOk(upload(linux, linuxManager, sample()));
    assertTrue(revision() > before);
    assertEquals(2, (int) saved.json("$.bundle.fileCount"));
    assertEquals(LOG_TEXT.length() + 5, ((Number) saved.json("$.bundle.unpackedBytes")).intValue());
    assertEquals(List.of("rami"), saved.json("$.bundle.owners"));
    assertEquals(64, ((String) saved.json("$.bundle.sha256")).length());
    assertEquals(
      db.queryForObject("SELECT name FROM app_user WHERE id = ?", String.class, managerIds.get(RoomCode.LINUX)),
      saved.json("$.bundle.uploadedBy")
    );
    assertEquals(25 * 1024 * 1024, (int) saved.json("$.maxBundleUploadBytes"));
    assertFalse(saved.body().contains("\"archive\""), "the summary carries no archive");

    byte[] stored = storedArchive();
    assertEquals(sha256(stored), saved.json("$.bundle.sha256"), "the digest is the archive's SHA-256");
    Map<String, TarMember> tar = tarByPath(stored);
    assertEquals(LOG_TEXT, new String(tar.get("srv/rami/logs/a.log").data(), StandardCharsets.UTF_8));
    assertEquals(2000, tar.get("srv/rami/logs/a.log").uid());
    assertEquals(0640, tar.get("srv/rami/logs/a.log").mode());

    Response list = expectOk(get("/rooms/" + linux + "/terminal-files", linuxManager));
    String savedSha = saved.json("$.bundle.sha256");
    assertEquals(savedSha, list.<String>json("$.bundle.sha256"));
    assertFalse(list.body().contains("OVERRIDE"));
  }

  @Test
  void onlyTheRoomsManagerAndTheAdminMayUploadReplaceRemoveOrTest() {
    byte[] zip = sample();
    expectError(upload(linux, managers.get(RoomCode.AI), zip), 404, "Room not found.");
    expectError(upload(linux, charlie.token(), zip), 403, "Access denied.");
    assertEquals(401, upload(linux, null, zip).status());
    expectOk(upload(linux, admin, zip));
    expectOk(upload(linux, linuxManager, zip));

    String path = "/rooms/" + linux + "/terminal-bundle";
    expectError(get(path, managers.get(RoomCode.AI)), 404, "Room not found.");
    expectError(get(path, charlie.token()), 403, "Access denied.");
    assertEquals(200, download(path, linuxManager, null).getStatus());
    assertEquals(200, download(path, admin, null).getStatus());
    expectError(delete(path, managers.get(RoomCode.AI)), 404, "Room not found.");
    expectError(delete(path, charlie.token()), 403, "Access denied.");
    expectOk(delete(path, linuxManager));
    expectError(delete(path, linuxManager), 404, "This room has no server package.");
  }

  @Test
  void rejectedPackagesListEveryProblemAndStoreNothing() {
    long before = revision();
    byte[] bad = zip(
      files("../up.txt", "x", "bin/sh", "x", "srv/ok.txt", "x", "devfest-manifest.csv", "srv/ok.txt,BAD,rami,0644\nnowhere,rami,rami,0644\n")
    );
    Response rejected = upload(linux, linuxManager, bad);
    assertEquals(400, rejected.status(), rejected.body());
    assertEquals("The package has 4 problems.", rejected.detail());
    List<String> problems = rejected.json("$.problems");
    assertEquals(4, problems.size(), problems.toString());
    assertTrue(problems.stream().anyMatch(p -> p.contains("..")));
    assertTrue(problems.stream().anyMatch(p -> p.contains("belongs to the Linux image")));
    assertTrue(problems.stream().anyMatch(p -> p.contains("not a valid owner name")));
    assertTrue(problems.stream().anyMatch(p -> p.contains("matches nothing")));
    assertEquals(before, revision());
    assertEquals(0, (int) db.queryForObject("SELECT count(*) FROM room_terminal_bundle", Integer.class));
    assertEquals(0, (int) db.queryForObject("SELECT count(*) FROM audit_event WHERE action LIKE 'EVENT_TERMINAL_BUNDLE%'", Integer.class));
  }

  @Test
  void aMissingFileAnOversizeUploadAndANonZipAreAnsweredPlainly() {
    MockHttpServletResponse none = perform(multipart(HttpMethod.PUT, "/api/v1/event/rooms/" + linux + "/terminal-bundle").header("Authorization", "Bearer " + linuxManager));
    assertEquals(400, none.getStatus());
    expectError(upload(linux, linuxManager, "not a zip".getBytes()), 400, "The file is not a ZIP archive.");
    byte[] huge = new byte[25 * 1024 * 1024 + 1];
    huge[0] = 'P';
    huge[1] = 'K';
    huge[2] = 3;
    huge[3] = 4;
    expectError(upload(linux, linuxManager, huge), 400, "The upload is larger than 25 MB.");
  }

  @Test
  void replacingIsAuditedWithHashAndCountsOnlyAndRemovingToo() {
    String first = expectOk(upload(linux, linuxManager, sample())).json("$.bundle.sha256");
    String second = expectOk(upload(linux, admin, zip(files("srv/other.txt", "different")))).json("$.bundle.sha256");
    assertNotEquals(first, second);
    expectOk(delete("/rooms/" + linux + "/terminal-bundle", linuxManager));

    List<Map<String, Object>> audits = db.queryForList(
      "SELECT action, target FROM audit_event WHERE action LIKE 'EVENT_TERMINAL_BUNDLE_%' ORDER BY id"
    );
    assertEquals(
      List.of("EVENT_TERMINAL_BUNDLE_UPLOADED", "EVENT_TERMINAL_BUNDLE_REPLACED", "EVENT_TERMINAL_BUNDLE_DELETED"),
      audits.stream().map(a -> a.get("action")).toList()
    );
    assertEquals("room:" + linux + " sha256:" + first + " files:2 bytes:" + (LOG_TEXT.length() + 5), audits.get(0).get("target"));
    assertEquals("room:" + linux + " sha256:" + second + " files:1 bytes:9 previous:" + first.substring(0, 16), audits.get(1).get("target"));
    assertEquals("room:" + linux + " sha256:" + second + " files:1 bytes:9", audits.get(2).get("target"));
    String everything = db.queryForList("SELECT action || ' ' || target || ' ' || COALESCE(reason, '') FROM audit_event", String.class).toString();
    assertFalse(everything.contains("OVERRIDE") || everything.contains("different"), "file content never reaches the audit log");
  }

  @Test
  void packagesFollowTheEditLockAndNeedTheTerminalEnabled() {
    loadContent();
    expectOk(upload(linux, linuxManager, sample()));
    expectOk(put("/admin/rooms/" + linux + "/terminal", admin, "{\"enabled\":false}"));
    expectError(upload(linux, linuxManager, sample()), 409, "The terminal is not enabled for this room.");
    expectOk(put("/admin/rooms/" + linux + "/terminal", admin, "{\"enabled\":true}"));

    openFinal();
    expectError(upload(linux, linuxManager, sample()), 409, READ_ONLY);
    expectError(delete("/rooms/" + linux + "/terminal-bundle", linuxManager), 409, READ_ONLY);
    assertEquals(200, download("/rooms/" + linux + "/terminal-bundle", linuxManager, null).getStatus(), "reading stays possible");
    assertEquals(false, expectOk(get("/rooms/" + linux + "/terminal-files", linuxManager)).json("$.editable"));
  }

  // ---- delivery -------------------------------------------------------------------------------------

  @Test
  void theBundleHasExactlyTheGatesOfTheTextFiles() {
    expectOk(upload(linux, linuxManager, sample()));
    expectOk(post("/rooms/" + linux + "/terminal-files", linuxManager, "{\"path\":\"logs/x.log\",\"content\":\"x\"}"));
    String files = "/team/rooms/" + linux + "/terminal-files";
    String bundle = "/team/rooms/" + linux + "/terminal-bundle";

    sameStatus(files, bundle, 404, "round 0");
    assignRound(1);
    sameStatus(files, bundle, 200, "assigned");
    assertEquals(404, get(bundle, alpha.token()).status(), "another group in the same round");
    startTimer();
    sameStatus(files, bundle, 200, "running");
    expectOk(post("/admin/round/timer/pause", admin, null));
    sameStatus(files, bundle, 200, "paused");
    expectOk(post("/admin/round/timer/resume", admin, null));

    expectOk(post("/admin/teams/" + charlie.id() + "/disqualify", admin, "{\"reason\":\"Bundle test\"}"));
    sameStatus(files, bundle, 404, "disqualified");
    expectOk(post("/admin/teams/" + charlie.id() + "/reinstate", admin, "{\"reason\":\"Bundle test\"}"));
    sameStatus(files, bundle, 200, "reinstated");

    expectOk(put("/admin/rooms/" + linux + "/terminal", admin, "{\"enabled\":false}"));
    sameStatus(files, bundle, 404, "terminal disabled");
    expectOk(put("/admin/rooms/" + linux + "/terminal", admin, "{\"enabled\":true}"));

    expectOk(put("/admin/overrides/room-exception", admin, "{\"teamId\":%d,\"roomId\":%d,\"reason\":\"Bundle test\"}".formatted(charlie.id(), rooms.get(RoomCode.AI))));
    sameStatus(files, bundle, 404, "moved out of the room by an exception");
    expectOk(put("/admin/overrides/room-exception", admin, "{\"teamId\":%d,\"roomId\":%d,\"reason\":\"Bundle test\"}".formatted(alpha.id(), linux)));
    assertEquals(200, get("/team/rooms/" + linux + "/terminal-bundle", alpha.token()).status(), "moved into the room by an exception");
    expectOk(call(HttpMethod.DELETE, "/admin/overrides/room-exception/" + charlie.id(), admin, "{\"reason\":\"Bundle test\"}", null));

    expectOk(post("/admin/round/timer/end", admin, null));
    sameStatus(files, bundle, 404, "TIME_UP");
    assignRound(2);
    sameStatus(files, bundle, 404, "round 2");
    assignRound(5);
    sameStatus(files, bundle, 404, "round 5");

    assertEquals(401, get(bundle, null).status());
    expectError(get(bundle, linuxManager), 403, "A team account is required.");
    expectError(get(bundle, admin), 403, "A team account is required.");
  }

  @Test
  void aRoomWithoutAPackageAnswers404AndKeepsItsTextFiles() {
    setRound(1);
    expectOk(post("/rooms/" + linux + "/terminal-files", linuxManager, "{\"path\":\"logs/x.log\",\"content\":\"hello\"}"));
    assertEquals(404, get("/team/rooms/" + linux + "/terminal-bundle", charlie.token()).status());
    Response files = expectOk(get("/team/rooms/" + linux + "/terminal-files", charlie.token()));
    assertEquals("hello", files.json("$.files[0].content"));
    assertNull(files.<Object>json("$.bundle"));
  }

  @Test
  void aRoomWithAPackageStartsFromThePackageAndTeamsLearnNothingElseAboutIt() {
    setRound(1);
    expectOk(post("/rooms/" + linux + "/terminal-files", linuxManager, "{\"path\":\"logs/x.log\",\"content\":\"text file\"}"));
    String sha = expectOk(upload(linux, linuxManager, sample())).json("$.bundle.sha256");
    Response files = expectOk(get("/team/rooms/" + linux + "/terminal-files", charlie.token()));
    assertEquals(List.of(), files.json("$.files"), "text files are not used when a package exists");
    assertEquals(sha, files.json("$.bundle.sha256"));
    assertEquals("rami:x:2000:2001:rami:/:/bin/sh\n", files.json("$.bundle.etcPasswd"));
    assertEquals("ops:x:2000:\nrami:x:2001:\n", files.json("$.bundle.etcGroup"));
    assertTrue(((Number) files.json("$.bundle.archiveBytes")).intValue() > 0);
    for (String leak : List.of("uploadedBy", "uploadedAt", "fileCount", "unpackedBytes", "owners", "MANAGER", "OVERRIDE")) {
      assertFalse(files.body().contains(leak), leak + " must not reach a team: " + files.body());
    }
    Response manager = expectOk(get("/rooms/" + linux + "/terminal-files/content", linuxManager));
    assertEquals(sha, manager.json("$.bundle.sha256"));
  }

  @Test
  void downloadHeadersAndBytesAreExact() {
    setRound(1);
    String sha = expectOk(upload(linux, linuxManager, sample())).json("$.bundle.sha256");
    MockHttpServletResponse response = download("/team/rooms/" + linux + "/terminal-bundle", charlie.token(), null);
    assertEquals(200, response.getStatus());
    assertEquals("application/gzip", response.getContentType());
    assertEquals("private, no-cache", response.getHeader(HttpHeaders.CACHE_CONTROL));
    assertEquals("\"" + sha + "\"", response.getHeader(HttpHeaders.ETAG));
    assertArrayEquals(storedArchive(), response.getContentAsByteArray());
    assertEquals(sha, sha256(response.getContentAsByteArray()));
    assertEquals(storedArchive().length, Integer.parseInt(response.getHeader(HttpHeaders.CONTENT_LENGTH)));
  }

  @Test
  void aMatchingEtagAnswers304WithoutLoadingTheArchive() {
    setRound(1);
    String sha = expectOk(upload(linux, linuxManager, sample())).json("$.bundle.sha256");
    clearInvocations(bundleRepository);

    for (String header : List.of("\"" + sha + "\"", "W/\"" + sha + "\"", "\"stale\", \"" + sha + "\"", "*")) {
      MockHttpServletResponse response = download("/team/rooms/" + linux + "/terminal-bundle", charlie.token(), header);
      assertEquals(304, response.getStatus(), header);
      assertEquals(0, response.getContentAsByteArray().length, header);
      assertEquals("\"" + sha + "\"", response.getHeader(HttpHeaders.ETAG));
      assertEquals("private, no-cache", response.getHeader(HttpHeaders.CACHE_CONTROL));
    }
    assertEquals(304, download("/rooms/" + linux + "/terminal-bundle", linuxManager, "\"" + sha + "\"").getStatus());
    verify(bundleRepository, never()).archive(anyLong());

    assertEquals(200, download("/team/rooms/" + linux + "/terminal-bundle", charlie.token(), "\"stale\"").getStatus());
    verify(bundleRepository, times(1)).archive(anyLong());
  }

  @Test
  void aWrongOrStaleEtagStillGetsTheBytesAndGatesAreCheckedBeforeTheEtag() {
    String sha = expectOk(upload(linux, linuxManager, sample())).json("$.bundle.sha256");
    // Round 0: even a matching ETag must not reveal that the room has a package.
    assertEquals(404, download("/team/rooms/" + linux + "/terminal-bundle", charlie.token(), "\"" + sha + "\"").getStatus());
    setRound(1);
    assertEquals(404, download("/team/rooms/" + linux + "/terminal-bundle", alpha.token(), "\"" + sha + "\"").getStatus());
    assertEquals(200, download("/team/rooms/" + linux + "/terminal-bundle", charlie.token(), "\"" + "0".repeat(64) + "\"").getStatus());
  }

  @Test
  void simultaneousDownloadsShareOneLoadAndAReplacementIsNeverServedStale() {
    setRound(1);
    expectOk(upload(linux, linuxManager, sample()));
    clearInvocations(bundleRepository);
    byte[] first = download("/team/rooms/" + linux + "/terminal-bundle", charlie.token(), null).getContentAsByteArray();
    download("/rooms/" + linux + "/terminal-bundle", linuxManager, null);
    download("/rooms/" + linux + "/terminal-bundle", admin, null);
    verify(bundleRepository, times(1)).archive(anyLong());

    String second = expectOk(upload(linux, linuxManager, zip(files("srv/new.txt", "new")))).json("$.bundle.sha256");
    MockHttpServletResponse after = download("/team/rooms/" + linux + "/terminal-bundle", charlie.token(), null);
    assertEquals(second, sha256(after.getContentAsByteArray()));
    assertFalse(java.util.Arrays.equals(first, after.getContentAsByteArray()));
    verify(bundleRepository, times(2)).archive(anyLong());
  }

  // ---- the second activity and what it must not change -------------------------------------------------

  @Test
  void theLinuxTerminalActivityIsAcceptedAndReplacesThePlainAnswerForm() {
    loadContent();
    expectOk(put("/admin/rooms/" + linux + "/activity", admin, "{\"activity\":\"LINUX_TERMINAL\"}"));
    assertEquals("LINUX_TERMINAL", db.queryForObject("SELECT activity FROM event_room WHERE id = ?", String.class, linux));
    assertEquals(List.of(), expectOk(get("/admin/board", admin)).json("$.alerts[?(@.code =~ /QUIZ.*/)]"), "the quiz checks ignore this activity");

    setRound(1);
    openRoom(RoomCode.LINUX);
    assertEquals("LINUX_TERMINAL", expectOk(get("/team/board", charlie.token())).json("$.scheduledRoom.activity"));
    expectError(submit(charlie, RoomCode.LINUX, java.util.UUID.randomUUID(), "output", CLUE_KEYS.get(RoomCode.LINUX)), 409, "This room records its result from its activity; there is no answer form.");
    assertEquals(0, (int) db.queryForObject("SELECT count(*) FROM room_submission", Integer.class));
  }

  @Test
  void aPackageCountsAsTerminalContentForTheAdminWarning() {
    assertEquals(1, ((List<?>) expectOk(get("/admin/board", admin)).json("$.alerts[?(@.code == 'TERMINAL_WITHOUT_FILES')]")).size());
    expectOk(upload(linux, linuxManager, sample()));
    assertEquals(List.of(), expectOk(get("/admin/board", admin)).json("$.alerts[?(@.code == 'TERMINAL_WITHOUT_FILES')]"));
  }

  // ---- helpers ------------------------------------------------------------------------------------

  private static byte[] sample() {
    Map<String, byte[]> entries = new LinkedHashMap<>(
      files("devfest-manifest.csv", "path,owner,group,mode\n/srv/rami/**,rami,ops,0640\n", "srv/rami/logs/a.log", LOG_TEXT)
    );
    entries.put("srv/pub/readme.txt", "hello".getBytes(StandardCharsets.UTF_8));
    return zip(entries);
  }

  private Response upload(long roomId, String token, byte[] zip) {
    var builder = multipart(HttpMethod.PUT, "/api/v1/event/rooms/" + roomId + "/terminal-bundle")
      .file(new MockMultipartFile("file", "package.zip", "application/zip", zip));
    if (token != null) builder.header("Authorization", "Bearer " + token);
    MockHttpServletResponse response = perform(builder);
    return new Response(response.getStatus(), bodyOf(response));
  }

  private MockHttpServletResponse download(String path, String token, String ifNoneMatch) {
    MockHttpServletRequestBuilder builder = request(HttpMethod.GET, "/api/v1/event" + path).header("Authorization", "Bearer " + token);
    if (ifNoneMatch != null) builder.header(HttpHeaders.IF_NONE_MATCH, ifNoneMatch);
    return perform(builder);
  }

  private MockHttpServletResponse perform(RequestBuilder builder) {
    try {
      return mvc.perform(builder).andReturn().getResponse();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String bodyOf(MockHttpServletResponse response) {
    try {
      return response.getContentAsString(StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private void sameStatus(String filesPath, String bundlePath, int expected, String situation) {
    Response files = get(filesPath, charlie.token());
    Response bundle = get(bundlePath, charlie.token());
    assertEquals(expected, files.status(), situation + " (files): " + files.body());
    assertEquals(expected, bundle.status(), situation + " (bundle): " + bundle.body());
    if (expected == 404) assertEquals(files.detail(), bundle.detail(), situation);
  }

  private byte[] storedArchive() {
    return db.queryForObject("SELECT archive FROM room_terminal_bundle WHERE room_id = ?", byte[].class, linux);
  }

  private static Map<String, TarMember> tarByPath(byte[] archive) {
    Map<String, TarMember> map = new LinkedHashMap<>();
    untar(archive).forEach(member -> map.put(member.path(), member));
    return map;
  }

  private static String sha256(byte[] bytes) throws RuntimeException {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
