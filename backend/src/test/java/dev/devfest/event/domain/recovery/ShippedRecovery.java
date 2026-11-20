package dev.devfest.event.domain.recovery;

import dev.devfest.event.domain.ShipmentContent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The recovery levels of the shipped docs/final/last-shipment-content.json, for the domain tests. */
final class ShippedRecovery {

  static final ObjectMapper JSON = JsonMapper.builder().build();

  private ShippedRecovery() {}

  static String file() {
    try {
      return Files.readString(Path.of("..", "docs", "final", "last-shipment-content.json"), StandardCharsets.UTF_8);
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  static RecoveryLevels levels() {
    return JSON.readValue(file(), ShipmentContent.class).recoveryLevels();
  }

  /** The recoveryLevels tree, to change before reading it back. */
  static ObjectNode tree() {
    return (ObjectNode) JSON.readTree(file()).get("recoveryLevels");
  }

  static RecoveryLevels read(ObjectNode tree) {
    return JSON.treeToValue(tree, RecoveryLevels.class);
  }

  /** The program's lines exactly as shipped (an untouched submission). */
  static List<String> originalLines() {
    return new ArrayList<>(levels().arduino().lines().stream().map(RecoveryLevels.CodeLine::code).toList());
  }
}
