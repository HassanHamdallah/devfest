package dev.devfest.event.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** The answer keys: the first four hold one value per line (blank lines are ignored); an empty key is allowed. */
public record TerminalKeysRequest(
  @NotNull @Size(max = 12000) String path,
  @NotNull @Size(max = 12000) String username,
  @NotNull @Size(max = 12000) String fileSize,
  @NotNull @Size(max = 12000) String pattern,
  @NotNull @Size(max = 1000) String encryptedMessage
) {}
