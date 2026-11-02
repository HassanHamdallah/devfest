package dev.devfest.event.dto;

import jakarta.validation.constraints.NotNull;

/** A team's answer to one question: the option's id (never its position on the screen). */
public record QuizAnswerRequest(@NotNull Long questionId, @NotNull Long optionId) {}
