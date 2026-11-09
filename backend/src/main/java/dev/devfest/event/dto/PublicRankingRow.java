package dev.devfest.event.dto;

import dev.devfest.event.domain.FinalStage;
import dev.devfest.event.domain.GroupCode;
import java.math.BigDecimal;
import java.util.List;

/**
 * One published ranking line: the four room scores (room order), the final score and the total. Deductions, cheating
 * records and the final's details never appear here. {@code rank} is null for a disqualified team.
 */
public record PublicRankingRow(
  Integer rank,
  String teamName,
  GroupCode groupCode,
  List<BigDecimal> roomScores,
  BigDecimal finalScore,
  BigDecimal total,
  FinalStage finalStatus,
  boolean disqualified
) {}
