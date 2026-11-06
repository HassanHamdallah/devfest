package dev.devfest.event.domain;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * Path rules for the server package a room's terminal installs (rules §18): paths are absolute from "/" but
 * written without the leading slash ({@code srv/rami/logs/a.log} installs {@code /srv/rami/logs/a.log}).
 */
public final class TerminalPackagePaths {

  /** Longest path the ustar header can hold: a 155-byte prefix, a "/" and a 100-byte name. */
  public static final int MAX_PATH_BYTES = 255;

  /** Directories whose whole tree belongs to the Linux image (or to the share the package arrives on). */
  private static final List<String> PROTECTED = List.of(
    "bin",
    "sbin",
    "lib",
    "usr/bin",
    "usr/sbin",
    "dev",
    "proc",
    "sys",
    "mnt",
    "etc/passwd",
    "etc/group"
  );

  private TerminalPackagePaths() {}

  /** Removes the one trailing "/" a ZIP directory entry carries. */
  public static String stripDirectorySlash(String name) {
    return name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
  }

  /**
   * Why {@code path} (no trailing slash) cannot be installed, or empty when it can: it must be relative, free of
   * "..", "." and empty parts, backslashes and control characters, fit a ustar header, and stay outside the
   * protected system directories.
   */
  public static Optional<String> problem(String path) {
    if (path.isEmpty()) return Optional.of("has an empty path");
    if (path.indexOf('\0') >= 0) return Optional.of("contains a NUL character");
    if (path.indexOf('\\') >= 0) return Optional.of("contains a backslash (use / between folders)");
    for (int i = 0; i < path.length(); i++) {
      char c = path.charAt(i);
      if (c < 0x20 || c == 0x7f) return Optional.of("contains a control character");
    }
    if (path.startsWith("/")) return Optional.of("is an absolute path (write it without the leading \"/\")");
    for (String part : path.split("/", -1)) {
      if (part.equals("..")) return Optional.of("contains \"..\"");
      if (part.isEmpty() || part.equals(".")) return Optional.of("has an empty or \".\" part");
    }
    if (path.getBytes(StandardCharsets.UTF_8).length > MAX_PATH_BYTES || TerminalTarWriter.split(path, false) == null) {
      return Optional.of("is too long for the archive format (255 bytes, 100 for the last folder or file name)");
    }
    for (String root : PROTECTED) {
      if (path.equals(root) || path.startsWith(root + "/")) {
        return Optional.of("is under /" + root + ", which belongs to the Linux image");
      }
    }
    return Optional.empty();
  }
}
