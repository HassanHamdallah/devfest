package dev.devfest.event.dto;

import java.util.List;

public record ResultsView(boolean published, List<PublicRankingRow> rows) {}
