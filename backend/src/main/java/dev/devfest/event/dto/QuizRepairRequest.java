package dev.devfest.event.dto;

import java.util.List;

/**
 * A repair to create or replace, and one repair of an import file. {@code snippets} are the code pieces in their
 * correct order; {@code points} defaults to 10 when null. Checked by QuizContentRules.
 */
public record QuizRepairRequest(String title, String instructions, Integer points, List<String> snippets) {}
