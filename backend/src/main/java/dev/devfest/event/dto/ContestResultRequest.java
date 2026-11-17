package dev.devfest.event.dto;

import dev.devfest.event.domain.ContestProblem;
import dev.devfest.event.domain.ContestStanding;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** One team's typed result: the problems it solved (A to F) and its standing in the Codeforces standings (required). */
public record ContestResultRequest(@NotNull List<ContestProblem> problems, @NotNull ContestStanding standing) {}
