package dev.devfest.event.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/** A team's order for one repair: every snippet id of the repair exactly once, first line first. */
public record QuizRepairOrderRequest(@NotNull List<Long> snippetIds) {}
