package dev.devfest.event.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** REVOKE removes a RECOVERY clue only when {@code alsoRemoveRecovery} is true. */
public record ManualClueRequest(
  @NotNull Long teamId,
  @NotNull Long roomId,
  @NotNull Action action,
  Boolean alsoRemoveRecovery,
  @NotBlank @Size(min = 3, max = 500) String reason
) {
  public enum Action {
    GRANT,
    REVOKE,
  }
}
