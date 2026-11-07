package dev.devfest.event.dto;

import dev.devfest.event.domain.ContestProblem;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

/**
 * One team's typed result: the problems it solved (A to E) and its penalty in whole minutes. The penalty is read as a
 * decimal so that 3.5 is refused instead of silently becoming 3.
 */
public record ContestResultRequest(@NotNull List<ContestProblem> problems, @NotNull BigDecimal penaltyMinutes) {}
