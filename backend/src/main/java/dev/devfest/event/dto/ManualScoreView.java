package dev.devfest.event.dto;

import java.math.BigDecimal;

public record ManualScoreView(long teamId, long roomId, BigDecimal score) {}
