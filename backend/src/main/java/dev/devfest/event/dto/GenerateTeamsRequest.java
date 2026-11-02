package dev.devfest.event.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Teams "<prefix> 01" … with member accounts "<prefix without spaces, lower case>01@event.local" …. */
public record GenerateTeamsRequest(
  @NotNull @Min(1) @Max(64) Integer count,
  @NotNull
  @Size(min = 1, max = 30)
  @Pattern(regexp = "[A-Za-z0-9 ]*[A-Za-z0-9][A-Za-z0-9 ]*", message = "Use letters, digits and spaces.")
  String prefix
) {}
