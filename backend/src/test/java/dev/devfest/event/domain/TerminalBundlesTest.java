package dev.devfest.event.domain;

import static dev.devfest.event.domain.TerminalTestPackages.files;
import static dev.devfest.event.domain.TerminalTestPackages.text;
import static dev.devfest.event.domain.TerminalTestPackages.untar;
import static dev.devfest.event.domain.TerminalTestPackages.zip;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.devfest.event.domain.TerminalBundles.Built;
import dev.devfest.event.domain.TerminalTestPackages.TarMember;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TerminalBundlesTest {

  private static final byte[] A_LOG = text("10:01 door ok\n");

  private static List<String> rejected(byte[] zip) {
    return assertThrows(TerminalPackageRejectedException.class, () -> TerminalBundles.build(zip)).problems();
  }

  private static String all(List<String> problems) {
    return String.join("\n", problems);
  }

  private static Map<String, TarMember> byPath(Built built) {
    Map<String, TarMember> map = new LinkedHashMap<>();
    untar(built.archive()).forEach(member -> map.put(member.path(), member));
    return map;
  }

  // ---- A valid package ------------------------------------------------------------------------------

  @Test
  void aPlainPackageInstallsAbsoluteFromRootWithDefaultsAndTheZipTimes() {
    Built built = TerminalBundles.build(zip(files("srv/rami/logs/a.log", "10:01 door ok\n", "srv/rami/logs/b.log", "x")));
    Map<String, TarMember> members = byPath(built);
    assertEquals(List.of("srv/rami/logs/a.log", "srv/rami/logs/b.log"), List.copyOf(members.keySet()), "parents are left to tar");
    TarMember file = members.get("srv/rami/logs/a.log");
    assertEquals(0644, file.mode());
    assertEquals(0, file.uid());
    assertEquals(0, file.gid());
    assertEquals(LocalDateTime.of(2026, 3, 14, 9, 30, 0).toEpochSecond(ZoneOffset.UTC), file.mtime(), "a DOS time is read as UTC");
    assertArrayEquals(A_LOG, file.data());
    assertEquals(2, built.fileCount());
    assertEquals(A_LOG.length + 1, built.unpackedBytes());
    assertEquals(64, built.sha256().length());
    assertEquals("", built.accounts().passwdText());
  }

  @Test
  void theSameZipAlwaysBuildsTheSameBundle() {
    byte[] zip = zip(files("a/x.txt", "1", "a/y.txt", "2", "b.txt", "3"));
    assertEquals(TerminalBundles.build(zip).sha256(), TerminalBundles.build(zip).sha256());
  }

  @Test
  void anExtendedTimestampIsUtcAndIgnoresTheServerTimeZone() throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream out = new ZipOutputStream(bytes)) {
      ZipEntry entry = new ZipEntry("srv/t.txt");
      entry.setLastModifiedTime(FileTime.from(Instant.parse("2026-05-01T12:00:00Z")));
      out.putNextEntry(entry);
      out.write(1);
      out.closeEntry();
    }
    assertEquals(Instant.parse("2026-05-01T12:00:00Z").getEpochSecond(), byPath(TerminalBundles.build(bytes.toByteArray())).get("srv/t.txt").mtime());
  }

  @Test
  void binaryFilesKeepEveryByte() {
    byte[] binary = new byte[10_000];
    for (int i = 0; i < binary.length; i++) binary[i] = (byte) (i * 31 + i / 256);
    Map<String, byte[]> entries = new LinkedHashMap<>();
    entries.put("srv/data.bin", binary);
    Built built = TerminalBundles.build(zip(entries));
    assertArrayEquals(binary, byPath(built).get("srv/data.bin").data());
    assertEquals(10_000, built.unpackedBytes());
  }

  @Test
  void aLongPathInstalls() {
    String path = "srv/" + "d".repeat(60) + "/" + "e".repeat(60) + "/" + "f".repeat(90) + ".log";
    assertTrue(path.length() > 100);
    assertEquals(path, byPath(TerminalBundles.build(zip(files(path, "x")))).keySet().iterator().next());
  }

  // ---- Ownership manifest ---------------------------------------------------------------------------

  @Test
  void theManifestSetsOwnersGroupsAndModesAndIsNotInstalled() {
    Map<String, byte[]> entries = files(
      "devfest-manifest.csv",
      "path,owner,group,mode\n/srv/rami/**,rami,ops,0640\n/srv/rami/key.txt,tech9,tech9,0600\n",
      "srv/rami/notes.txt",
      "n",
      "srv/rami/key.txt",
      "k",
      "srv/public/readme.txt",
      "r"
    );
    Built built = TerminalBundles.build(zip(entries));
    Map<String, TarMember> members = byPath(built);
    assertFalse(members.containsKey("devfest-manifest.csv"));
    assertEquals(2000, members.get("srv/rami/notes.txt").uid(), "rami is the first owner by name");
    assertEquals(2000, members.get("srv/rami/notes.txt").gid(), "ops is the first group by name");
    assertEquals(0640, members.get("srv/rami/notes.txt").mode());
    TarMember key = members.get("srv/rami/key.txt");
    assertEquals(2001, key.uid());
    assertEquals(2002, key.gid(), "tech9's own group follows ops and rami");
    assertEquals(0600, key.mode());
    TarMember other = members.get("srv/public/readme.txt");
    assertEquals(0, other.uid());
    assertEquals(0644, other.mode());
    assertEquals("rami,tech9", built.accounts().ownersText());
    assertEquals("rami:x:2000:2001:rami:/:/bin/sh\ntech9:x:2001:2002:tech9:/:/bin/sh\n", built.accounts().passwdText());
    assertEquals(3, built.fileCount());
  }

  @Test
  void aFolderCoveredByARuleIsWrittenWithExecuteWhereItHasRead() {
    Built built = TerminalBundles.build(
      zip(files("devfest-manifest.csv", "srv/rami/**,rami,rami,0640\nsrv/rami/private,rami,rami,0700\n", "srv/rami/a.txt", "a", "srv/rami/private/b.txt", "b"))
    );
    Map<String, TarMember> members = byPath(built);
    assertEquals(0750, members.get("srv/rami").mode());
    assertEquals('5', members.get("srv/rami").type());
    assertEquals(0700, members.get("srv/rami/private").mode(), "an exact folder rule is used as written");
    assertEquals(0640, members.get("srv/rami/a.txt").mode());
    assertEquals(2000, members.get("srv/rami").uid());
  }

  @Test
  void anEmptyFolderIsKeptButAnOrdinaryParentFolderIsNotWritten() {
    Map<String, byte[]> entries = new LinkedHashMap<>(files("srv/a.txt", "a"));
    entries.put("srv/", new byte[0]);
    entries.put("var/spool/empty/", new byte[0]);
    Map<String, TarMember> members = byPath(TerminalBundles.build(zip(entries)));
    assertEquals(List.of("srv/a.txt", "var/spool/empty"), List.copyOf(members.keySet()));
    assertEquals(0755, members.get("var/spool/empty").mode());
  }

  // ---- Rejections: each rule on its own ---------------------------------------------------------------

  @ParameterizedTest
  @ValueSource(strings = { "srv/../etc/x", "../x", "srv/a/../../b" })
  void rejectsDotDot(String path) {
    assertTrue(all(rejected(zip(files(path, "x")))).contains("contains \"..\""), path);
  }

  @Test
  void rejectsAbsoluteBackslashNulAndEmptyPartPaths() {
    String absolute = all(rejected(zip(files("/srv/a.log", "x"))));
    assertTrue(absolute.contains("is an absolute path"), absolute);
    assertTrue(all(rejected(zip(files("srv\\a.log", "x")))).contains("contains a backslash"));
    assertTrue(all(rejected(zip(files("srv/a\0.log", "x")))).contains("contains a NUL character"));
    assertTrue(all(rejected(zip(files("srv//a.log", "x")))).contains("empty or \".\" part"));
    assertTrue(all(rejected(zip(files("srv/./a.log", "x")))).contains("empty or \".\" part"));
    assertTrue(all(rejected(zip(files("srv/a\n.log", "x")))).contains("control character"));
  }

  @ParameterizedTest
  @ValueSource(
    strings = {
      "bin/sh",
      "sbin/init",
      "lib/libc.so",
      "usr/bin/env",
      "usr/sbin/x",
      "dev/null",
      "proc/1/x",
      "sys/x",
      "mnt/bundle",
      "etc/passwd",
      "etc/group",
      "bin/deep/er/x",
    }
  )
  void rejectsProtectedPaths(String path) {
    assertTrue(all(rejected(zip(files(path, "x", "srv/ok.txt", "ok")))).contains("belongs to the Linux image"), path);
  }

  @Test
  void otherEtcFilesAndLookalikePathsAreFine() {
    Map<String, TarMember> members = byPath(
      TerminalBundles.build(zip(files("etc/motd", "x", "binary/x", "y", "usr/bin2/z", "z", "etc/passwd.bak", "p", "mnt2/x", "m")))
    );
    assertEquals(5, members.size());
  }

  @Test
  void rejectsPathsTheArchiveFormatCannotHold() {
    assertTrue(all(rejected(zip(files("srv/" + "x".repeat(101), "x")))).contains("too long for the archive format"));
    assertTrue(all(rejected(zip(files("a/".repeat(130) + "z", "x")))).contains("too long for the archive format"));
  }

  @Test
  void rejectsMoreThan5000Entries() {
    Map<String, byte[]> many = new LinkedHashMap<>();
    for (int i = 0; i <= 5000; i++) many.put("srv/f" + i + ".txt", new byte[] { 1 });
    assertTrue(all(rejected(zip(many))).contains("more than 5000 entries"));
    many.remove("srv/f0.txt");
    assertEquals(5000, TerminalBundles.build(zip(many)).fileCount(), "exactly 5000 is allowed");
  }

  @Test
  void rejectsMoreThan20MbUnpackedByCountingRealBytes() {
    // 21 MB of zeros deflates to ~21 KB: the claim "small zip" is true, the unpacked size is not.
    Map<String, byte[]> bomb = new LinkedHashMap<>();
    bomb.put("srv/zeros.bin", new byte[21 * 1024 * 1024]);
    byte[] zip = zip(bomb);
    assertTrue(zip.length < 100_000, "the ZIP itself is tiny: " + zip.length);
    assertTrue(all(rejected(zip)).contains("unpacks to more than 20 MB"));
  }

  @Test
  void twentyMbUnpackedExactlyIsAllowed() {
    Map<String, byte[]> entries = new LinkedHashMap<>();
    entries.put("srv/zeros.bin", new byte[20 * 1024 * 1024]);
    assertEquals(20L * 1024 * 1024, TerminalBundles.build(zip(entries)).unpackedBytes());
  }

  @Test
  void rejectsAnUploadOver25Mb() {
    byte[] huge = new byte[25 * 1024 * 1024 + 1];
    huge[0] = 'P';
    huge[1] = 'K';
    huge[2] = 3;
    huge[3] = 4;
    assertEquals(List.of("The upload is larger than 25 MB."), rejected(huge));
  }

  @Test
  void rejectsNonZipAndEmptyInput() {
    assertEquals(List.of("The file is not a ZIP archive."), rejected("hello".getBytes()));
    assertEquals(List.of("The file is not a ZIP archive."), rejected(new byte[0]));
    assertEquals(List.of("The package has no files."), rejected(zip(new LinkedHashMap<>())));
    Map<String, byte[]> onlyFolders = new LinkedHashMap<>();
    onlyFolders.put("srv/", new byte[0]);
    assertEquals(List.of("The package has no files."), rejected(zip(onlyFolders)));
  }

  @Test
  void rejectsAFileUsedAsAFolderAndAFileAndFolderWithOneName() {
    String used = all(rejected(zip(files("srv/a", "x", "srv/a/b", "y"))));
    assertTrue(used.contains("\"srv/a\" is a file, but other entries are inside it"), used);
    Map<String, byte[]> both = new LinkedHashMap<>(files("srv/a", "x"));
    both.put("srv/a/", new byte[0]);
    assertTrue(all(rejected(zip(both))).contains("appears more than once (as a file and a folder)"));
  }

  @Test
  void rejectsInvalidManifestRowsAndRowsThatMatchNothing() {
    String problems = all(
      rejected(
        zip(
          files(
            "devfest-manifest.csv",
            "srv/a.txt,Rami,rami,0644\nsrv/a.txt,rami,rami,9999\nnothing/here.txt,rami,rami,0644\nsrv/missing/**,rami,rami,0644\nsrv/ok.txt,rami,rami,0644\n",
            "srv/ok.txt",
            "x"
          )
        )
      )
    );
    assertTrue(problems.contains("devfest-manifest.csv line 1: \"Rami\" is not a valid owner name"), problems);
    assertTrue(problems.contains("line 2: \"9999\" is not a valid mode"), problems);
    assertTrue(problems.contains("line 3: the path nothing/here.txt matches nothing in the package"), problems);
    assertTrue(problems.contains("line 4: the path srv/missing/** matches nothing in the package"), problems);
    assertFalse(problems.contains("line 5"), problems);
  }

  @Test
  void aRuleMayCoverAFolderThatOnlyExistsBecauseOfFilesBelowIt() {
    Built built = TerminalBundles.build(zip(files("devfest-manifest.csv", "srv/logs/**,rami,rami,0644\n", "srv/logs/deep/a.log", "x")));
    assertEquals(2000, byPath(built).get("srv/logs/deep/a.log").uid());
  }

  @Test
  void showsEveryProblemAtOnce() {
    List<String> problems = rejected(
      zip(
        files(
          "../up.txt",
          "x",
          "/abs.txt",
          "x",
          "bin/sh",
          "x",
          "etc/passwd",
          "x",
          "srv/ok.txt",
          "x",
          "devfest-manifest.csv",
          "srv/ok.txt,BAD,rami,0644\nmissing,rami,rami,0644\n"
        )
      )
    );
    String all = all(problems);
    assertTrue(problems.size() >= 6, all);
    assertTrue(all.contains("\"..\""), all);
    assertTrue(all.contains("absolute"), all);
    assertTrue(all.contains("\"bin/sh\""), all);
    assertTrue(all.contains("\"etc/passwd\""), all);
    assertTrue(all.contains("not a valid owner name"), all);
    assertTrue(all.contains("matches nothing"), all);
  }

  @Test
  void aDamagedZipIsReportedNotThrown() {
    byte[] zip = zip(files("srv/a.txt", "some text that is long enough to be damaged"));
    byte[] damaged = java.util.Arrays.copyOf(zip, zip.length / 2);
    assertFalse(rejected(damaged).isEmpty());
  }

  @Test
  void aManifestThatIsNotUtf8IsReported() {
    Map<String, byte[]> entries = new LinkedHashMap<>(files("srv/a.txt", "x"));
    entries.put("devfest-manifest.csv", new byte[] { (byte) 0xff, (byte) 0xfe, 'x' });
    assertTrue(all(rejected(zip(entries))).contains("not valid UTF-8"));
  }
}
