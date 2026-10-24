package dev.devfest.event.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SubmissionRequest(
  @NotBlank @Size(max = 4000) String technicalOutput,
  @NotBlank @Size(max = 500) String clueAnswer
) {}
