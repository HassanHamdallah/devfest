package dev.devfest.event.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** The five values of a Save or Final submit; blanks are fine, each is at most 500 bytes (checked by the service). */
public record TerminalFormRequest(
  @NotNull @Size(max = 500) String path,
  @NotNull @Size(max = 500) String username,
  @NotNull @Size(max = 500) String fileSize,
  @NotNull @Size(max = 500) String pattern,
  @NotNull @Size(max = 500) String finalClue
) {}
