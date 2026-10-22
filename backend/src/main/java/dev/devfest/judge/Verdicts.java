package dev.devfest.judge;

public final class Verdicts {

  private Verdicts() {}

  public static String normalize(String s) {
    return s
      .replace("\r\n", "\n")
      .replace('\r', '\n')
      .replaceAll("(?m)[ \t]+$", "")
      .replaceAll("\n+$", "");
  }

  public static String verdict(
    JudgeClient.Result r,
    String expected,
    boolean run,
    int memoryLimit
  ) {
    return switch (r.status()) {
      case 1 -> "QUEUED";
      case 2 -> "RUNNING";
      case 3 -> run || normalize(r.stdout()).equals(normalize(expected))
        ? "ACCEPTED"
        : "WRONG_ANSWER";
      case 4 -> "WRONG_ANSWER";
      case 5 -> "TIME_LIMIT_EXCEEDED";
      case 6 -> "COMPILATION_ERROR";
      case 7, 8, 9, 10, 11, 12, 14 -> r.memory() != null &&
      r.memory() >= memoryLimit
        ? "MEMORY_LIMIT_EXCEEDED"
        : "RUNTIME_ERROR";
      default -> "SYSTEM_ERROR";
    };
  }
}
