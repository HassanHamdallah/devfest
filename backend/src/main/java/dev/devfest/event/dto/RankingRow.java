package dev.devfest.event.dto;

/**
 * The admin's ranking row: the team's place and its full score breakdown. {@code rank} is null for a disqualified team,
 * which stays outside the ranking and is listed last.
 */
public record RankingRow(Integer rank, TeamScoreBreakdown scores) {}
