package dev.devfest.event.dto;

import java.util.List;

/**
 * A question to create or replace, and one question of an import file. {@code section} is EASY, MEDIUM, ADVANCED,
 * LANDMARK or RECENT; {@code correctOption} is the 0-based index into {@code options}. Checked by
 * QuizContentRules (400 with a readable message), so no bean validation here.
 */
public record QuizQuestionRequest(
  String section,
  String prompt,
  List<String> options,
  Integer correctOption,
  Integer points,
  String explanation
) {}
