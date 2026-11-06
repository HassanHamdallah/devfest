package dev.devfest.event.domain;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/**
 * Reads an uploaded ZIP package into memory under hard limits: 25 MB uploaded, 5000 entries and 20 MB unpacked.
 * The unpacked size is counted from the bytes actually inflated, never from the sizes the ZIP claims, so a zip
 * bomb stops at the limit. Every problem found is collected instead of stopping at the first.
 */
public final class TerminalPackageReader {

  public static final int MAX_UPLOAD_BYTES = 25 * 1024 * 1024;
  public static final int MAX_ENTRIES = 5000;
  public static final long MAX_UNPACKED_BYTES = 20L * 1024 * 1024;
  /** Used when a ZIP entry has no usable time: 2020-01-01T00:00:00Z. */
  static final long DEFAULT_MTIME = 1_577_836_800L;
  /** Stops a package of thousands of bad paths from producing an endless report. */
  private static final int MAX_PROBLEMS = 100;

  /** A file or an explicit directory of the package; {@code path} has no leading or trailing slash. */
  public record Member(String path, boolean directory, byte[] data, long mtimeSeconds) {}

  /**
   * @param manifest the text of devfest-manifest.csv, null when the package has none
   * @param complete false when reading stopped at a limit, so later checks on what is missing would mislead
   */
  public record Result(List<Member> members, String manifest, boolean complete, List<String> problems) {}

  private TerminalPackageReader() {}

  public static Result read(byte[] zip) {
    List<String> problems = new ArrayList<>();
    if (zip.length > MAX_UPLOAD_BYTES) {
      problems.add("The upload is larger than 25 MB.");
      return new Result(List.of(), null, false, problems);
    }
    if (!looksLikeZip(zip)) {
      problems.add("The file is not a ZIP archive.");
      return new Result(List.of(), null, false, problems);
    }
    Map<String, Member> members = new HashMap<>();
    String manifest = null;
    boolean complete = true;
    int entries = 0;
    long unpacked = 0;
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
      ZipEntry entry;
      byte[] buffer = new byte[64 * 1024];
      while ((entry = in.getNextEntry()) != null) {
        if (++entries > MAX_ENTRIES) {
          problems.add("The package has more than " + MAX_ENTRIES + " entries.");
          complete = false;
          break;
        }
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int n;
        while ((n = in.read(buffer)) > 0) {
          unpacked += n;
          if (unpacked > MAX_UNPACKED_BYTES) break;
          data.write(buffer, 0, n);
        }
        if (unpacked > MAX_UNPACKED_BYTES) {
          problems.add("The package unpacks to more than 20 MB.");
          complete = false;
          break;
        }
        String name = entry.getName();
        boolean directory = entry.isDirectory();
        String path = TerminalPackagePaths.stripDirectorySlash(name);
        if (!directory && path.equals(TerminalManifest.FILE_NAME)) {
          manifest = decode(data.toByteArray(), problems);
          continue;
        }
        var problem = TerminalPackagePaths.problem(path);
        if (problem.isPresent()) {
          problems.add("\"" + printable(name) + "\" " + problem.get() + ".");
          continue;
        }
        Member earlier = members.get(path);
        if (earlier != null) {
          problems.add("\"" + path + "\" appears more than once" + (earlier.directory() != directory ? " (as a file and a folder)" : "") + ".");
          continue;
        }
        members.put(path, new Member(path, directory, directory ? null : data.toByteArray(), mtime(entry)));
      }
    } catch (ZipException e) {
      problems.add("The ZIP file is damaged or uses an unsupported feature (" + printable(String.valueOf(e.getMessage())) + ").");
      complete = false;
    } catch (IOException e) {
      problems.add("The ZIP file could not be read.");
      complete = false;
    }
    List<Member> sorted = members.values().stream().sorted((a, b) -> a.path().compareTo(b.path())).toList();
    return new Result(sorted, manifest, complete, capped(problems));
  }

  private static boolean looksLikeZip(byte[] zip) {
    return zip.length >= 4 && zip[0] == 'P' && zip[1] == 'K' && (zip[2] == 3 || zip[2] == 5) && (zip[3] == 4 || zip[3] == 6);
  }

  private static String decode(byte[] bytes, List<String> problems) {
    try {
      String text = StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(java.nio.ByteBuffer.wrap(bytes))
        .toString();
      return text.startsWith("﻿") ? text.substring(1) : text;
    } catch (CharacterCodingException e) {
      problems.add(TerminalManifest.FILE_NAME + " is not valid UTF-8 text.");
      return "";
    }
  }

  /**
   * The entry's modification time in epoch seconds. An extended-timestamp field (0x5455) is UTC already; a plain
   * DOS time is a wall-clock time that is read as UTC, so the result never depends on the server's time zone.
   */
  private static long mtime(ZipEntry entry) {
    if (hasExtendedTime(entry.getExtra()) && entry.getLastModifiedTime() != null) {
      return entry.getLastModifiedTime().toInstant().getEpochSecond();
    }
    if (entry.getTimeLocal() == null) return DEFAULT_MTIME;
    return entry.getTimeLocal().toEpochSecond(ZoneOffset.UTC);
  }

  private static boolean hasExtendedTime(byte[] extra) {
    if (extra == null) return false;
    int i = 0;
    while (i + 4 <= extra.length) {
      int id = (extra[i] & 0xff) | ((extra[i + 1] & 0xff) << 8);
      int size = (extra[i + 2] & 0xff) | ((extra[i + 3] & 0xff) << 8);
      if (id == 0x5455) return true;
      i += 4 + size;
    }
    return false;
  }

  /** A name safe to show in a message: control characters are replaced. */
  private static String printable(String text) {
    String shown = text.length() > 120 ? text.substring(0, 120) + "…" : text;
    return shown.replaceAll("[\\p{Cntrl}]", "?");
  }

  private static List<String> capped(List<String> problems) {
    if (problems.size() <= MAX_PROBLEMS) return problems;
    List<String> shown = new ArrayList<>(problems.subList(0, MAX_PROBLEMS));
    shown.add("…and " + (problems.size() - MAX_PROBLEMS) + " more problems.");
    return shown;
  }
}
