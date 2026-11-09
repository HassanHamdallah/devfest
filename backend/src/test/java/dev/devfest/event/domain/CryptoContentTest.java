package dev.devfest.event.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.devfest.event.domain.CryptoContent.Evidence;
import dev.devfest.event.domain.CryptoContent.Layer;
import dev.devfest.event.domain.CryptoContent.Recovery;
import dev.devfest.event.domain.CryptoContent.Report;
import dev.devfest.event.domain.CryptoContent.Signatures;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The shipped docs/final/crypto-content.json, the values the server derives from it, and the validation rules. */
class CryptoContentTest {

  static CryptoContent shipped() throws IOException {
    Path file = Path.of("..", "docs", "final", "crypto-content.json");
    return JsonMapper.builder().build().readValue(Files.readString(file, StandardCharsets.UTF_8), CryptoContent.class);
  }

  private static CryptoContent withLayers(CryptoContent c, List<Layer> layers) {
    return new CryptoContent(c.format(), c.fullUntilMinutes(), layers, c.signatures(), c.evidence(), c.suspects(), c.correctSuspect(), c.recovery());
  }

  @Test
  void theShippedFileIsValid() throws IOException {
    CryptoContent content = shipped();
    assertEquals(List.of(), CryptoContentRules.problems(content));
    assertEquals(25, content.fullUntilMinutes());
    assertEquals(6, content.suspects().size());
    assertEquals("Leila Nasser", content.correctSuspect());
  }

  @Test
  void theServerDerivesEachLayersPlaintext() throws IOException {
    CryptoContent content = shipped();
    assertEquals(52, content.plaintext(1));
    assertEquals(17, content.plaintext(2));
    assertEquals(8, content.plaintext(3));
    assertEquals(17, content.plaintext(4));
    for (int layer = 1; layer < 4; layer++) {
      assertEquals(content.plaintext(layer), content.layers().get(layer).c(), "layer " + (layer + 1) + " ciphertext is the previous plaintext");
    }
  }

  @Test
  void theServerDerivesTheVerdicts() throws IOException {
    CryptoContent content = shipped();
    assertFalse(content.signatureValid("E03"));
    assertTrue(content.signatureValid("E04"));
    assertFalse(content.verdictYes(3));
    assertTrue(content.verdictYes(4));
  }

  @Test
  void theFileCarriesTheExactEventData() throws IOException {
    CryptoContent content = shipped();
    List<long[]> expected = List.of(new long[] { 26, 91, 7, 13, 29 }, new long[] { 52, 77, 7, 11, 43 }, new long[] { 17, 55, 5, 11, 27 }, new long[] { 8, 33, 3, 11, 3 });
    for (int i = 0; i < 4; i++) {
      Layer l = content.layers().get(i);
      assertEquals(List.of(expected.get(i)[0], expected.get(i)[1], expected.get(i)[2], expected.get(i)[3], expected.get(i)[4]), List.of(l.c(), l.n(), l.p(), l.q(), l.d()));
    }
    assertEquals(7L, content.signatures().e());
    assertEquals(33L, content.signatures().n());
    assertEquals(17L, content.signatures().h());
    assertEquals(10L, content.report("E03").orElseThrow().s());
    assertEquals(29L, content.report("E04").orElseThrow().s());
    assertEquals(List.of("Kareem Shehab", "Leila Nasser", "Tariq Al-Khatib", "Nadia Murad", "Farid Mansour", "Mazen Haddad"), content.suspects());
    assertEquals("Rami Al-Khatib", content.evidenceOf(3).claimedSigner());
    assertEquals("Rami Al-Khatib", content.evidenceOf(4).claimedSigner());
    assertTrue(content.recoveryIsPlaceholder());
  }

  @Test
  void aBrokenChainIsReported() throws IOException {
    CryptoContent content = shipped();
    List<Layer> layers = new ArrayList<>(content.layers());
    Layer second = layers.get(1);
    layers.set(1, new Layer(2, 53L, second.n(), second.p(), second.q(), second.d()));
    List<String> problems = CryptoContentRules.problems(withLayers(content, layers));
    assertTrue(problems.contains("Layer 2: c must equal the previous layer's plaintext."), problems.toString());
    assertFalse(problems.stream().anyMatch(p -> p.startsWith("Layer 1")), problems.toString());
  }

  @Test
  void aWrongModulusOrNonPrimeFactorIsReported() throws IOException {
    CryptoContent content = shipped();
    List<Layer> layers = new ArrayList<>(content.layers());
    Layer first = layers.get(0);
    layers.set(0, new Layer(1, first.c(), 92L, first.p(), first.q(), first.d()));
    assertTrue(CryptoContentRules.problems(withLayers(content, layers)).contains("Layer 1: n must equal p x q."));
    layers.set(0, new Layer(1, first.c(), 91L, 1L, 91L, first.d()));
    assertTrue(CryptoContentRules.problems(withLayers(content, layers)).contains("Layer 1: p and q must be prime."));
  }

  @Test
  void everyProblemIsListedAtOnce() throws IOException {
    CryptoContent content = shipped();
    CryptoContent broken = new CryptoContent(
      2,
      0,
      content.layers().subList(0, 3),
      new Signatures(7L, 33L, 99L, List.of(new Report("E03", 10L))),
      content.evidence().subList(0, 3),
      content.suspects().subList(0, 5),
      "Nobody",
      new Recovery("", List.of())
    );
    List<String> problems = CryptoContentRules.problems(broken);
    assertTrue(problems.size() >= 8, problems.toString());
    assertTrue(problems.stream().anyMatch(p -> p.startsWith("format")));
    assertTrue(problems.stream().anyMatch(p -> p.startsWith("fullUntilMinutes")));
    assertTrue(problems.stream().anyMatch(p -> p.startsWith("layers")));
    assertTrue(problems.stream().anyMatch(p -> p.startsWith("signatures.h")));
    assertTrue(problems.stream().anyMatch(p -> p.startsWith("signatures.reports")));
    assertTrue(problems.stream().anyMatch(p -> p.startsWith("evidence")));
    assertTrue(problems.stream().anyMatch(p -> p.startsWith("suspects")));
    assertTrue(problems.stream().anyMatch(p -> p.startsWith("correctSuspect")));
    assertTrue(problems.stream().anyMatch(p -> p.startsWith("recovery")));
  }

  @Test
  void signedEvidenceNeedsAClaimedSignerAndOthersMustNotHaveOne() throws IOException {
    CryptoContent content = shipped();
    List<Evidence> evidence = new ArrayList<>(content.evidence());
    evidence.set(2, new Evidence("E03", evidence.get(2).text(), null));
    evidence.set(0, new Evidence("E01", evidence.get(0).text(), "Someone"));
    CryptoContent broken = new CryptoContent(1, 25, content.layers(), content.signatures(), evidence, content.suspects(), content.correctSuspect(), content.recovery());
    List<String> problems = CryptoContentRules.problems(broken);
    assertTrue(problems.contains("Evidence E03: claimedSigner is required."));
    assertTrue(problems.contains("Evidence E01: claimedSigner is only for signed reports."));
  }

  @Test
  void recoveryAnswersUseTheClueNormalizer() throws IOException {
    CryptoContent content = shipped();
    assertTrue(content.acceptsRecoveryAnswer("  placeholder-replace-me "));
    assertFalse(content.acceptsRecoveryAnswer("placeholder replace me"));
  }
}
