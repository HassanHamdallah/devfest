package dev.devfest.event.dto;

import java.util.List;

/**
 * The AI room's whole content as one JSON file (export, and import which replaces everything). {@code format} is
 * 1; {@code mcqMinutes} and {@code passScore} are optional on import (the room keeps its value when absent).
 */
public record QuizDocument(
  Integer format,
  Integer mcqMinutes,
  Integer passScore,
  List<QuizQuestionRequest> questions,
  List<QuizRepairRequest> repairs
) {
  public static final int FORMAT = 1;
}
