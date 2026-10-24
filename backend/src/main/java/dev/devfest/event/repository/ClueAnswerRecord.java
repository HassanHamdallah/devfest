package dev.devfest.event.repository;

import dev.devfest.event.domain.ClueOverride;
import java.util.UUID;

/** A submission's clue answer and its current override: what a key re-check needs. */
public record ClueAnswerRecord(UUID submissionId, String clueAnswer, ClueOverride override) {}
