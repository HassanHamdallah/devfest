package dev.devfest.event.dto;

import java.math.BigDecimal;

/**
 * A team's points in a points-and-phone room: {@code total} out of 100, {@code score} (total x 0.15, out of 15, before
 * hints and cheating) and {@code undoDelta}, the change "Undo last" would reverse (null when there is nothing to undo).
 */
public record PointsView(long teamId, long roomId, int total, BigDecimal score, Integer undoDelta) {}
