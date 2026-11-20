package dev.devfest.event.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** One Arduino run: every program line as the team edited it, and the letter of the chosen missing-behaviour option. */
public record ArduinoRunRequest(@NotNull @Size(max = 400) List<String> lines, @NotNull @Size(max = 2) String option) {}
