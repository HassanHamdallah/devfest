package dev.devfest.judge;

import dev.devfest.contest.ProblemData;

public interface JudgeClient {
  record Result(
    int status,
    String stdout,
    String stderr,
    String compileOutput,
    String message,
    Long memory
  ) {}

  void verifyRuntime(ProblemData.Language language);
  String dispatch(
    String source,
    int languageId,
    String input,
    double cpuSeconds,
    int memoryKb
  );
  Result poll(String token);
}
