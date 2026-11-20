package dev.devfest.event.dto;

import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * One ML stage's answer; the stage (path) decides which field is read: 1 {@code features} (the ones kept), 2
 * {@code order} (every step id), 3 {@code missing} and {@code outliers}, 4 {@code model}, 5 {@code threshold}, 6
 * {@code word}. The other fields are ignored.
 */
public record MlCheckRequest(
  @Size(max = 20) List<@Size(max = 40) String> features,
  @Size(max = 20) List<@Size(max = 40) String> order,
  @Size(max = 40) String missing,
  @Size(max = 40) String outliers,
  @Size(max = 40) String model,
  BigDecimal threshold,
  @Size(max = 100) String word
) {}
