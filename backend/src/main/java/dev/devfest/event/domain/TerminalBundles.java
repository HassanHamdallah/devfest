package dev.devfest.event.domain;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Turns an uploaded ZIP into the gzip-compressed ustar bundle a room's terminal installs (rules §18):
 * validation of every rule at once, the optional ownership manifest applied, deterministic numeric ids for the
 * owners it names. Pure: bytes in, bytes out.
 */
public final class TerminalBundles {

  public static final int DEFAULT_FILE_MODE = 0644;
  public static final int DEFAULT_DIRECTORY_MODE = 0755;
  private static final int MAX_PROBLEMS = 100;

  /** The finished bundle: {@code archive} is gzip(ustar), {@code sha256} its lower-case hex digest. */
  public record Built(byte[] archive, String sha256, int fileCount, long unpackedBytes, TerminalAccounts accounts) {}

  private record Resolved(String path, TerminalPackageReader.Member member, String owner, String group, int mode) {}

  private TerminalBundles() {}

  /** @throws TerminalPackageRejectedException with every problem found, when the package breaks a rule */
  public static Built build(byte[] zip) {
    TerminalPackageReader.Result read = TerminalPackageReader.read(zip);
    List<String> problems = new ArrayList<>(read.problems());
    List<TerminalManifest.Rule> rules = List.of();
    if (read.manifest() != null) {
      TerminalManifest.Parsed parsed = TerminalManifest.parse(read.manifest());
      problems.addAll(parsed.problems());
      rules = parsed.rules();
    }

    Map<String, TerminalPackageReader.Member> members = new HashMap<>();
    read.members().forEach(member -> members.put(member.path(), member));
    Set<String> implicitDirectories = implicitDirectories(read.members(), members, problems);
    if (read.complete()) {
      Set<String> known = new HashSet<>(members.keySet());
      known.addAll(implicitDirectories);
      for (TerminalManifest.Rule rule : rules) {
        if (known.stream().noneMatch(path -> TerminalManifest.covers(rule, path))) {
          problems.add(
            TerminalManifest.FILE_NAME + " line " + rule.line() + ": the path " + rule.written() + " matches nothing in the package."
          );
        }
      }
      if (problems.isEmpty() && read.members().stream().allMatch(TerminalPackageReader.Member::directory)) {
        problems.add("The package has no files.");
      }
    }
    if (!problems.isEmpty()) throw new TerminalPackageRejectedException(capped(problems));

    List<Resolved> installed = resolve(read.members(), implicitDirectories, rules);
    TerminalAccounts accounts = TerminalAccounts.of(
      installed.stream().map(Resolved::owner).toList(),
      installed.stream().map(Resolved::group).toList()
    );
    List<TerminalTarWriter.Entry> entries = new ArrayList<>();
    int files = 0;
    long bytes = 0;
    for (Resolved item : installed) {
      int uid = accounts.uid(item.owner());
      int gid = accounts.gid(item.group());
      TerminalPackageReader.Member member = item.member();
      if (member != null && !member.directory()) {
        entries.add(TerminalTarWriter.Entry.file(item.path(), member.data(), item.mode(), uid, gid, member.mtimeSeconds()));
        files++;
        bytes += member.data().length;
      } else {
        long mtime = member != null ? member.mtimeSeconds() : TerminalPackageReader.DEFAULT_MTIME;
        entries.add(TerminalTarWriter.Entry.directory(item.path(), item.mode(), uid, gid, mtime));
      }
    }
    byte[] archive = TerminalTarWriter.gzip(entries);
    return new Built(archive, sha256(archive), files, bytes, accounts);
  }

  /** Folders that exist only because a file lies below them; a file used as a folder is a problem. */
  private static Set<String> implicitDirectories(
    List<TerminalPackageReader.Member> list,
    Map<String, TerminalPackageReader.Member> members,
    List<String> problems
  ) {
    Set<String> implicit = new TreeSet<>();
    Set<String> reported = new HashSet<>();
    for (TerminalPackageReader.Member member : list) {
      int slash = member.path().indexOf('/');
      while (slash >= 0) {
        String parent = member.path().substring(0, slash);
        TerminalPackageReader.Member existing = members.get(parent);
        if (existing == null) {
          implicit.add(parent);
        } else if (!existing.directory() && reported.add(parent)) {
          problems.add("\"" + parent + "\" is a file, but other entries are inside it.");
        }
        slash = member.path().indexOf('/', slash + 1);
      }
    }
    return implicit;
  }

  /**
   * Every installed path with its owner, group and mode, parents before children. A folder is written into the
   * archive when a rule covers it or when it is empty; other folders are left to tar, which creates them (and
   * never changes the owner or mode of a folder that already exists in the image).
   */
  private static List<Resolved> resolve(
    List<TerminalPackageReader.Member> list,
    Set<String> implicitDirectories,
    List<TerminalManifest.Rule> rules
  ) {
    Set<String> hasChildren = new HashSet<>(implicitDirectories);
    for (TerminalPackageReader.Member member : list) {
      int slash = member.path().lastIndexOf('/');
      if (slash > 0) hasChildren.add(member.path().substring(0, slash));
    }
    TreeSet<String> paths = new TreeSet<>(implicitDirectories);
    list.forEach(member -> paths.add(member.path()));
    Map<String, TerminalPackageReader.Member> byPath = new HashMap<>();
    list.forEach(member -> byPath.put(member.path(), member));

    List<Resolved> installed = new ArrayList<>();
    for (String path : paths) {
      TerminalPackageReader.Member member = byPath.get(path);
      boolean directory = member == null || member.directory();
      Optional<TerminalManifest.Rule> rule = TerminalManifest.match(rules, path);
      if (directory && rule.isEmpty() && hasChildren.contains(path)) continue;
      installed.add(
        new Resolved(
          path,
          member,
          rule.map(TerminalManifest.Rule::owner).orElse(TerminalAccounts.ROOT),
          rule.map(TerminalManifest.Rule::group).orElse(TerminalAccounts.ROOT),
          modeFor(directory, rule)
        )
      );
    }
    return installed;
  }

  /**
   * The rule's mode for a file or for a folder named exactly by the rule; a folder covered by a {@code /**} rule
   * gets the rule's mode plus execute wherever it has read, so the files below it stay reachable.
   */
  private static int modeFor(boolean directory, Optional<TerminalManifest.Rule> rule) {
    if (rule.isEmpty()) return directory ? DEFAULT_DIRECTORY_MODE : DEFAULT_FILE_MODE;
    int mode = rule.get().mode();
    if (directory && rule.get().recursive()) mode |= (mode & 0444) >> 2;
    return mode;
  }

  private static List<String> capped(List<String> problems) {
    if (problems.size() <= MAX_PROBLEMS + 1) return problems;
    List<String> shown = new ArrayList<>(problems.subList(0, MAX_PROBLEMS));
    shown.add("…and " + (problems.size() - MAX_PROBLEMS) + " more problems.");
    return shown;
  }

  static String sha256(byte[] bytes) {
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
