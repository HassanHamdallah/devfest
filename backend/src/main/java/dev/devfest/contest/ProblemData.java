package dev.devfest.contest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public record ProblemData(
  @NotBlank @Size(max = 120) String title,
  @NotBlank @Size(max = 20000) String statement,
  @NotBlank @Size(max = 4000) String inputFormat,
  @NotBlank @Size(max = 4000) String outputFormat,
  @NotBlank @Size(max = 4000) String constraints,
  @NotEmpty @Size(max = 5) List<@Valid TestCase> examples,
  @NotEmpty @Size(max = 20) List<@Valid TestCase> tests,
  @NotEmpty @Size(max = 3) List<Language> languages,
  @DecimalMin("0.1") @DecimalMax("5") double cpuSeconds,
  @Min(32768) @Max(524288) int memoryKb
) {
  public record TestCase(
    @NotNull @Size(max = 65536) String input,
    @NotNull @Size(max = 65536) String output
  ) {}

  public enum Language {
    CPP(105, "C++ (GCC 14.1.0)"),
    JAVA(91, "Java (JDK 17.0.6)"),
    PYTHON(109, "Python (3.13.2)");

    public final int providerId;
    public final String runtime;

    Language(int id, String runtime) {
      providerId = id;
      this.runtime = runtime;
    }
  }

  public PublicProblem publicView(long id, int version) {
    return new PublicProblem(
      id,
      version,
      title,
      statement,
      inputFormat,
      outputFormat,
      constraints,
      examples,
      languages,
      cpuSeconds,
      memoryKb
    );
  }

  public record PublicProblem(
    long id,
    int version,
    String title,
    String statement,
    String inputFormat,
    String outputFormat,
    String constraints,
    List<TestCase> examples,
    List<Language> languages,
    double cpuSeconds,
    int memoryKb
  ) {}
}
