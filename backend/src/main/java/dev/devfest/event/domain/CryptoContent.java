package dev.devfest.event.domain;

import java.math.BigInteger;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The crypto final's content as imported by the admin (docs/final/crypto-content.json). Fields are boxed so a
 * missing value is reported by {@link CryptoContentRules} instead of failing the read. The values the team must
 * find (each layer's plaintext M and each signed report's verdict) are never stored: they are derived here with
 * {@link BigInteger#modPow}. Nothing in this record may reach a member except the public parts (C, n, S, e, H,
 * evidence text, claimed signer, suspect names).
 */
public record CryptoContent(
  Integer format,
  Integer fullUntilMinutes,
  List<Layer> layers,
  Signatures signatures,
  List<Evidence> evidence,
  List<String> suspects,
  String correctSuspect,
  Recovery recovery
) {
  public static final int FORMAT = 1;
  public static final int LAYER_COUNT = 4;
  public static final int SUSPECT_COUNT = 6;
  public static final int DEFAULT_FULL_UNTIL_MINUTES = 25;
  public static final List<String> EVIDENCE_IDS = List.of("E01", "E02", "E03", "E04");
  /** The signed reports: E03 belongs to layer 3, E04 to layer 4. */
  public static final List<String> SIGNED_EVIDENCE = List.of("E03", "E04");

  /** {@code c}: ciphertext, {@code n = p x q}, {@code d}: private exponent; M = C^d mod n is derived. */
  public record Layer(Integer layer, Long c, Long n, Long p, Long q, Long d) {}

  /** {@code h}: the hash a valid signature must reach; a report is valid when S^e mod n = H. */
  public record Signatures(Long e, Long n, Long h, List<Report> reports) {}

  public record Report(String evidence, Long s) {}

  public record Evidence(String id, String text, String claimedSigner) {}

  public record Recovery(String question, List<String> acceptedAnswers) {}

  /** The layer's plaintext (the integer a team must enter): C^d mod n. */
  public long plaintext(int layer) {
    Layer l = layers.get(layer - 1);
    return BigInteger.valueOf(l.c()).modPow(BigInteger.valueOf(l.d()), BigInteger.valueOf(l.n())).longValueExact();
  }

  /** True when the report S of the evidence verifies: S^e mod n = H. */
  public boolean signatureValid(String evidenceId) {
    return report(evidenceId)
      .map(r ->
        BigInteger.valueOf(r.s())
          .modPow(BigInteger.valueOf(signatures.e()), BigInteger.valueOf(signatures.n()))
          .equals(BigInteger.valueOf(signatures.h()))
      )
      .orElse(false);
  }

  /** The correct verdict for the signed report of {@code layer} (3 is E03, 4 is E04): YES when it verifies. */
  public boolean verdictYes(int layer) {
    return signatureValid(SIGNED_EVIDENCE.get(layer - 3));
  }

  public Optional<Report> report(String evidenceId) {
    return signatures.reports().stream().filter(r -> r.evidence().equals(evidenceId)).findFirst();
  }

  public Evidence evidenceOf(int layer) {
    return evidence.get(layer - 1);
  }

  public boolean isCorrectSuspect(int suspectId) {
    return suspectId >= 1 && suspectId <= suspects.size() && suspects.get(suspectId - 1).equals(correctSuspect);
  }

  /** The shipped file keeps an obvious placeholder until the real recovery question is written. */
  public boolean recoveryIsPlaceholder() {
    return recovery.question().strip().toUpperCase(Locale.ROOT).startsWith("PLACEHOLDER");
  }

  public boolean acceptsRecoveryAnswer(String answer) {
    return recovery.acceptedAnswers().stream().anyMatch(accepted -> AnswerNormalizer.matches(answer, accepted));
  }
}
