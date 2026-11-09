package dev.devfest.event.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** One integer, as typed. */
public record LayerAnswerRequest(@NotBlank @Size(max = 20) String value) {}
