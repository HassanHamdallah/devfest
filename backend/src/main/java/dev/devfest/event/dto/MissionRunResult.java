package dev.devfest.event.dto;

import tools.jackson.databind.JsonNode;

/** The result of one experiment, and how many different experiments the stage still allows (null: no limit). */
public record MissionRunResult(int stage, String config, JsonNode result, Integer runsLeft) {}
