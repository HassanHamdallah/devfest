package dev.devfest.event;

import static dev.devfest.event.domain.TerminalTestPackages.files;
import static dev.devfest.event.domain.TerminalTestPackages.untar;
import static dev.devfest.event.domain.TerminalTestPackages.zip;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.RoomCode;
import dev.devfest.event.domain.TerminalTestPackages.TarMember;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The final's terminal package: kept apart from every room's package, uploaded by the admin until the final opens, and
 * downloaded by a team only while its final shows the case.
 */
class EventFinalTerminalBundleTest extends EventIntegrationTest {

  private static final String FINAL_BUNDLE = "/admin/final/terminal-bundle";
  private static final String TEAM_BUNDLE = "/team/final/terminal-bundle";

  private TestTeam alpha;
  private long linux;
  private String linuxManager;

  @BeforeEach
  void prepare() {
    loadContent();
    alpha = team("Alpha", GroupCode.A);
    for (RoomCode code : RoomCode.values()) {
      db.update("INSERT INTO collected_clue(team_id, room_id, source, collected_at) VALUES (?, ?, 'MANUAL', now())", alpha.id(), rooms.get(code));
    }
    linux = rooms.get(RoomCode.LINUX);
    linuxManager = managers.get(RoomCode.LINUX);
  }

  @Test
  void uploadingReplacingAndDeletingTheFinalPackageLeavesTheLinuxRoomPackageAndItsEtagUnchanged() {
    expectOk(uploadRoom(zip(files("srv/linux/readme.txt", "the Linux room"))));
    Map<String, Object> roomBefore = db.queryForMap("SELECT * FROM room_terminal_bundle WHERE room_id = ?", linux);
    MockHttpServletResponse roomDownload = download("/rooms/" + linux + "/terminal-bundle", linuxManager, null);
    assertEquals(200, roomDownload.getStatus());
    String roomEtag = roomDownload.getHeader(HttpHeaders.ETAG);

    Response uploaded = expectOk(uploadFinal(admin, shippedFinalPackage()));
    assertEquals(true, uploaded.json("$.uploaded"));
    assertEquals(12, (int) uploaded.json("$.fileCount"), "8 clue files and 4 private keys");
    String firstFinal = uploaded.json("$.sha256");
    assertNotEquals(roomEtag, "\"" + firstFinal + "\"");
    String secondFinal = expectOk(uploadFinal(admin, zip(files("root/other.txt", "replaced")))).json("$.sha256");
    assertNotEquals(firstFinal, secondFinal);
    expectOk(delete(FINAL_BUNDLE, admin));
    expectOk(uploadFinal(admin, shippedFinalPackage()));

    Map<String, Object> roomAfter = db.queryForMap("SELECT * FROM room_terminal_bundle WHERE room_id = ?", linux);
    assertEquals(roomBefore.keySet(), roomAfter.keySet());
    for (String column : roomBefore.keySet()) {
      Object before = roomBefore.get(column);
      Object after = roomAfter.get(column);
      if (before instanceof byte[] bytes) assertArrayEquals(bytes, (byte[]) after, column);
      else assertEquals(before, after, column);
    }
    assertEquals(1, (int) db.queryForObject("SELECT count(*) FROM room_terminal_bundle", Integer.class), "no room row added");
    assertEquals(1, (int) db.queryForObject("SELECT count(*) FROM final_terminal_bundle", Integer.class));
    MockHttpServletResponse again = download("/rooms/" + linux + "/terminal-bundle", linuxManager, roomEtag);
    assertEquals(304, again.getStatus(), "the room's ETag still matches");
    assertEquals(roomEtag, again.getHeader(HttpHeaders.ETAG));
    assertArrayEquals(roomDownload.getContentAsByteArray(), download("/rooms/" + linux + "/terminal-bundle", linuxManager, null).getContentAsByteArray());
    // Deleting the room's package leaves the final's.
    expectOk(delete("/rooms/" + linux + "/terminal-bundle", linuxManager));
    assertEquals(true, expectOk(get(FINAL_BUNDLE, admin)).json("$.uploaded"));
  }

  @Test
  void onlyTheAdminManagesTheFinalPackageAndOnlyUntilTheFinalOpens() {
    byte[] shipped = shippedFinalPackage();
    assertEquals(403, uploadFinal(linuxManager, shipped).status());
    assertEquals(403, uploadFinal(alpha.token(), shipped).status());
    assertEquals(401, uploadFinal(null, shipped).status());
    assertEquals(403, get(FINAL_BUNDLE, alpha.token()).status());
    assertEquals(403, delete(FINAL_BUNDLE, linuxManager).status());
    assertEquals(false, expectOk(get(FINAL_BUNDLE, admin)).json("$.uploaded"));
    expectError(delete(FINAL_BUNDLE, admin), 404, "The final has no terminal package.");

    Response refused = uploadFinal(admin, zip(files("../escape.txt", "x")));
    assertEquals(400, refused.status(), refused.body());
    assertTrue(((List<?>) refused.json("$.problems")).size() >= 1);

    assertTrue(expectOk(get("/admin/board", admin)).<List<String>>json("$.alerts[*].code").contains("MISSING_FINAL_TERMINAL_PACKAGE"));
    long before = revision();
    Response saved = expectOk(uploadFinal(admin, shipped));
    assertTrue(!expectOk(get("/admin/board", admin)).<List<String>>json("$.alerts[*].code").contains("MISSING_FINAL_TERMINAL_PACKAGE"));
    assertTrue(revision() > before);
    assertEquals(false, saved.json("$.readOnly"));
    assertEquals(db.queryForObject("SELECT name FROM app_user WHERE role = 'ADMIN' LIMIT 1", String.class), saved.json("$.uploadedBy"));
    List<String> actions = db.queryForList("SELECT action FROM audit_event WHERE action LIKE 'EVENT_FINAL_TERMINAL_%'", String.class);
    assertEquals(List.of("EVENT_FINAL_TERMINAL_BUNDLE_UPLOADED"), actions);

    openFinal();
    expectError(uploadFinal(admin, shipped), 409, "Event content is read-only after the final opens.");
    expectError(delete(FINAL_BUNDLE, admin), 409, "Event content is read-only after the final opens.");
    assertEquals(true, expectOk(get(FINAL_BUNDLE, admin)).json("$.readOnly"));
    assertEquals(200, download(FINAL_BUNDLE + "/archive", admin, null).getStatus(), "the admin can still test it");
  }

  @Test
  void aTeamDownloadsTheFinalPackageOnlyWhileItsFinalShowsTheCase() {
    TestTeam bravo = team("Bravo", GroupCode.B);
    String sha = expectOk(uploadFinal(admin, shippedFinalPackage())).json("$.sha256");
    assertEquals(404, download(TEAM_BUNDLE, alpha.token(), null).getStatus(), "the final is not open");
    openFinal();
    assertEquals(404, download(TEAM_BUNDLE, alpha.token(), null).getStatus(), "unlock screen");
    assertEquals(404, download(TEAM_BUNDLE, bravo.token(), null).getStatus(), "not qualified");
    expectOk(unlockWithKeys(alpha));
    assertEquals(404, download(TEAM_BUNDLE, alpha.token(), null).getStatus(), "mission screen");
    expectOk(startCase(alpha));

    MockHttpServletResponse first = download(TEAM_BUNDLE, alpha.token(), null);
    assertEquals(200, first.getStatus());
    assertEquals("\"" + sha + "\"", first.getHeader(HttpHeaders.ETAG));
    assertEquals("private, no-cache", first.getHeader(HttpHeaders.CACHE_CONTROL));
    assertEquals("application/gzip", first.getContentType());
    List<String> paths = untar(first.getContentAsByteArray()).stream().map(TarMember::path).toList();
    assertTrue(paths.contains("root/DevFest-Crypto/participant_package/keys/key_A.pem"), paths.toString());
    assertTrue(paths.stream().noneMatch(path -> path.contains("secret")), paths.toString());
    MockHttpServletResponse cached = download(TEAM_BUNDLE, alpha.token(), first.getHeader(HttpHeaders.ETAG));
    assertEquals(304, cached.getStatus());
    assertEquals(0, cached.getContentAsByteArray().length);
    Response view = expectOk(get("/team/final", alpha.token()));
    assertEquals(sha, view.json("$.terminal.sha256"));
    assertEquals(first.getContentAsByteArray().length, (int) view.json("$.terminal.archiveBytes"));

    assertEquals(403, download(TEAM_BUNDLE, admin, null).getStatus(), "a team endpoint");
    openInvestigationPhase();
    assertEquals(409, download(TEAM_BUNDLE, alpha.token(), null).getStatus(), "the decryption phase is over: the terminal is not offered");
    assertNull(expectOk(get("/team/final", alpha.token())).json("$.terminal"));
    expectOk(suspect(alpha, CORRECT_SUSPECT));
    assertEquals(404, download(TEAM_BUNDLE, alpha.token(), null).getStatus(), "submitted");
  }

  // ---- helpers ------------------------------------------------------------------------------------

  /** The organizers' built package for the case (docs/final/last-shipment/final-package.zip). */
  private static byte[] shippedFinalPackage() {
    try {
      return Files.readAllBytes(Path.of("..", "docs", "final", "last-shipment", "final-package.zip"));
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private Response uploadFinal(String token, byte[] zip) {
    return multipartPut("/api/v1/event" + FINAL_BUNDLE, token, zip);
  }

  private Response uploadRoom(byte[] zip) {
    return multipartPut("/api/v1/event/rooms/" + linux + "/terminal-bundle", linuxManager, zip);
  }

  private Response multipartPut(String url, String token, byte[] zip) {
    var builder = multipart(HttpMethod.PUT, url).file(new MockMultipartFile("file", "package.zip", "application/zip", zip));
    if (token != null) builder.header("Authorization", "Bearer " + token);
    MockHttpServletResponse response = perform(builder);
    try {
      return new Response(response.getStatus(), response.getContentAsString(StandardCharsets.UTF_8));
    } catch (java.io.UnsupportedEncodingException e) {
      throw new IllegalStateException(e);
    }
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
}
