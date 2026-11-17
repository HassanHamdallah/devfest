package dev.devfest.event.dto;

import dev.devfest.event.domain.FinalReport;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * One of the listed suspects, by the id the final screen listed ("S11"), with the optional Final Investigation Report:
 * four pieces of evidence and an explanation. Each text is trimmed (blank means none), at most 600 characters and holds
 * no control character except a new line.
 */
public record SuspectChoiceRequest(
  @NotBlank @Size(max = 10) String suspectId,
  @Size(max = FinalReport.MAX_CHARS) @Pattern(regexp = TEXT, message = NO_CONTROLS) String evidence1,
  @Size(max = FinalReport.MAX_CHARS) @Pattern(regexp = TEXT, message = NO_CONTROLS) String evidence2,
  @Size(max = FinalReport.MAX_CHARS) @Pattern(regexp = TEXT, message = NO_CONTROLS) String evidence3,
  @Size(max = FinalReport.MAX_CHARS) @Pattern(regexp = TEXT, message = NO_CONTROLS) String evidence4,
  @Size(max = FinalReport.MAX_CHARS) @Pattern(regexp = TEXT, message = NO_CONTROLS) String explanation
) {
  /** Any character that is not a control character, or a new line. */
  static final String TEXT = "[\\P{Cntrl}\\n]*";
  static final String NO_CONTROLS = "Remove the control characters; only new lines are allowed.";

  public SuspectChoiceRequest {
    evidence1 = clean(evidence1);
    evidence2 = clean(evidence2);
    evidence3 = clean(evidence3);
    evidence4 = clean(evidence4);
    explanation = clean(explanation);
  }

  /** A request that names only the suspect (no report). */
  public SuspectChoiceRequest(String suspectId) {
    this(suspectId, null, null, null, null, null);
  }

  public FinalReport report() {
    return new FinalReport(evidence1, evidence2, evidence3, evidence4, explanation);
  }

  private static String clean(String text) {
    if (text == null) return null;
    String trimmed = text.strip();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
