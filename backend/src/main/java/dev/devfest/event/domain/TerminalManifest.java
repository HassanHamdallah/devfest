package dev.devfest.event.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The optional {@code devfest-manifest.csv} of a terminal package: one rule per line,
 * {@code path,owner,group,mode}. A path ends in {@code /**} to cover a folder and everything below it; the most
 * specific rule wins (an exact path beats any {@code /**} rule, a deeper folder beats a shallower one).
 */
public final class TerminalManifest {

  public static final String FILE_NAME = "devfest-manifest.csv";
  private static final Pattern MODE = Pattern.compile("[0-7]{3,4}");
  private static final String HEADER = "path,owner,group,mode";

  /** {@code base} has no leading or trailing slash ("" = the whole tree); {@code line} is 1-based. */
  public record Rule(int line, String written, String base, boolean recursive, String owner, String group, int mode) {}

  /** The rules read from a manifest and every problem found in it. */
  public record Parsed(List<Rule> rules, List<String> problems) {}

  private TerminalManifest() {}

  public static Parsed parse(String csv) {
    List<Rule> rules = new ArrayList<>();
    List<String> problems = new ArrayList<>();
    Map<String, Integer> seen = new HashMap<>();
    String[] lines = csv.split("\r?\n", -1);
    boolean first = true;
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i].strip();
      int number = i + 1;
      if (line.isEmpty() || line.startsWith("#")) continue;
      if (first && line.replace(" ", "").equalsIgnoreCase(HEADER)) {
        first = false;
        continue;
      }
      first = false;
      List<String> fields = fields(line);
      if (fields == null || fields.size() != 4) {
        problems.add(FILE_NAME + " line " + number + ": expected path,owner,group,mode.");
        continue;
      }
      List<String> own = new ArrayList<>();
      Rule rule = parseRule(number, fields, own);
      own.forEach(problem -> problems.add(FILE_NAME + " line " + number + ": " + problem));
      if (rule == null) continue;
      String key = (rule.recursive() ? "**:" : "=:") + rule.base();
      Integer earlier = seen.putIfAbsent(key, number);
      if (earlier == null) rules.add(rule);
      else problems.add(FILE_NAME + " line " + number + ": the path " + rule.written() + " repeats line " + earlier + ".");
    }
    return new Parsed(List.copyOf(rules), List.copyOf(problems));
  }

  /** The most specific rule covering {@code path} (a file or folder, no leading or trailing slash). */
  public static Optional<Rule> match(List<Rule> rules, String path) {
    return rules.stream().filter(rule -> covers(rule, path)).max(Comparator.comparing(TerminalManifest::specificity));
  }

  public static boolean covers(Rule rule, String path) {
    if (!rule.recursive()) return rule.base().equals(path);
    return rule.base().isEmpty() || path.equals(rule.base()) || path.startsWith(rule.base() + "/");
  }

  /** Exact paths outrank every {@code /**} rule; a longer folder outranks a shorter one. */
  private static long specificity(Rule rule) {
    return (rule.recursive() ? 0L : 1_000_000L) + rule.base().length();
  }

  /** The rule on one line, or null with the reasons added to {@code problems}. */
  private static Rule parseRule(int line, List<String> fields, List<String> problems) {
    String written = fields.get(0).strip();
    String owner = fields.get(1).strip();
    String group = fields.get(2).strip();
    String modeText = fields.get(3).strip();
    boolean recursive = false;
    String base = written.startsWith("/") ? written.substring(1) : written;
    if (base.equals("**")) {
      recursive = true;
      base = "";
    } else if (base.endsWith("/**")) {
      recursive = true;
      base = base.substring(0, base.length() - 3);
    } else if (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    if (base.contains("*")) {
      problems.add("only a trailing /** is supported in a path (" + written + ").");
    } else if (!recursive || !base.isEmpty()) {
      TerminalPackagePaths.problem(base).ifPresent(problem -> problems.add("the path " + written + " " + problem + "."));
    }
    if (!TerminalAccounts.VALID_NAME.matcher(owner).matches()) {
      problems.add("\"" + owner + "\" is not a valid owner name (lower-case letters, digits, _ and -, up to 32).");
    }
    if (!TerminalAccounts.VALID_NAME.matcher(group).matches()) {
      problems.add("\"" + group + "\" is not a valid group name (lower-case letters, digits, _ and -, up to 32).");
    }
    if (!MODE.matcher(modeText).matches()) {
      problems.add("\"" + modeText + "\" is not a valid mode (3 or 4 octal digits such as 0644).");
    }
    if (!problems.isEmpty()) return null;
    return new Rule(line, written, base, recursive, owner, group, Integer.parseInt(modeText, 8));
  }

  /** Splits one CSV line; double quotes may wrap a field (and "" inside them is a quote). Null: unbalanced quotes. */
  private static List<String> fields(String line) {
    List<String> fields = new ArrayList<>();
    StringBuilder field = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (quoted) {
        if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
          field.append('"');
          i++;
        } else if (c == '"') {
          quoted = false;
        } else {
          field.append(c);
        }
      } else if (c == '"') {
        quoted = true;
      } else if (c == ',') {
        fields.add(field.toString());
        field.setLength(0);
      } else {
        field.append(c);
      }
    }
    if (quoted) return null;
    fields.add(field.toString());
    return fields;
  }
}
