package dev.devfest.event.domain;

import dev.devfest.event.domain.CryptoContent.Evidence;
import dev.devfest.event.domain.CryptoContent.Layer;
import dev.devfest.event.domain.CryptoContent.Report;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Everything a crypto-final content file must satisfy. Every problem is listed, none stops the others. The derived
 * values are computed here: n = p x q, M = C^d mod n, and every next layer's C must equal the previous layer's M.
 */
public final class CryptoContentRules {

  /** Layer answers are entered as integers; keeping n below 10^9 keeps them well inside a long. */
  public static final long MAX_MODULUS = 1_000_000_000L;
  private static final int MAX_TEXT = 2000;
  private static final int MAX_NAME = 80;

  private CryptoContentRules() {}

  public static List<String> problems(CryptoContent content) {
    List<String> problems = new ArrayList<>();
    if (content == null) return List.of("The file is empty.");
    if (!Integer.valueOf(CryptoContent.FORMAT).equals(content.format())) {
      problems.add("format: must be " + CryptoContent.FORMAT + ".");
    }
    if (content.fullUntilMinutes() == null || content.fullUntilMinutes() < 1 || content.fullUntilMinutes() > 40) {
      problems.add("fullUntilMinutes: must be a whole number from 1 to 40.");
    }
    layers(content.layers(), problems);
    signatures(content.signatures(), problems);
    evidence(content.evidence(), problems);
    suspects(content, problems);
    recovery(content.recovery(), problems);
    return problems;
  }

  private static void layers(List<Layer> layers, List<String> problems) {
    if (layers == null || layers.size() != CryptoContent.LAYER_COUNT) {
      problems.add("layers: exactly " + CryptoContent.LAYER_COUNT + " layers are required.");
      return;
    }
    BigInteger previous = null;
    for (int i = 0; i < layers.size(); i++) {
      Layer l = layers.get(i);
      String where = "Layer " + (i + 1) + ": ";
      if (l == null || l.c() == null || l.n() == null || l.p() == null || l.q() == null || l.d() == null) {
        problems.add(where + "c, n, p, q and d are all required.");
        previous = null;
        continue;
      }
      if (l.layer() == null || l.layer() != i + 1) problems.add(where + "layer must be " + (i + 1) + ".");
      boolean sound = true;
      if (!prime(l.p()) || !prime(l.q())) {
        problems.add(where + "p and q must be prime.");
        sound = false;
      } else if (l.p().equals(l.q())) {
        problems.add(where + "p and q must differ.");
        sound = false;
      }
      if (l.n() < 2 || l.n() >= MAX_MODULUS) {
        problems.add(where + "n must be from 2 to " + (MAX_MODULUS - 1) + ".");
        sound = false;
      } else if (BigInteger.valueOf(l.p()).multiply(BigInteger.valueOf(l.q())).compareTo(BigInteger.valueOf(l.n())) != 0) {
        problems.add(where + "n must equal p x q.");
        sound = false;
      }
      if (l.c() < 1 || l.c() >= l.n()) {
        problems.add(where + "c must be from 1 to n - 1.");
        sound = false;
      }
      if (l.d() < 1) {
        problems.add(where + "d must be positive.");
        sound = false;
      }
      if (previous != null && BigInteger.valueOf(l.c()).compareTo(previous) != 0) {
        problems.add(where + "c must equal the previous layer's plaintext.");
      }
      previous = sound ? BigInteger.valueOf(l.c()).modPow(BigInteger.valueOf(l.d()), BigInteger.valueOf(l.n())) : null;
    }
  }

  private static void signatures(CryptoContent.Signatures s, List<String> problems) {
    if (s == null || s.e() == null || s.n() == null || s.h() == null || s.reports() == null) {
      problems.add("signatures: e, n, h and reports are required.");
      return;
    }
    if (s.e() < 2) problems.add("signatures.e: must be at least 2.");
    boolean modulusOk = s.n() >= 2 && s.n() < MAX_MODULUS;
    if (!modulusOk) problems.add("signatures.n: must be from 2 to " + (MAX_MODULUS - 1) + ".");
    else if (s.h() < 0 || s.h() >= s.n()) problems.add("signatures.h: must be from 0 to n - 1.");
    Set<String> seen = new HashSet<>();
    for (Report r : s.reports()) {
      if (r == null || r.evidence() == null || r.s() == null) {
        problems.add("signatures.reports: every report needs evidence and s.");
        continue;
      }
      if (!CryptoContent.SIGNED_EVIDENCE.contains(r.evidence())) {
        problems.add("signatures.reports: " + r.evidence() + " is not a signed report.");
      } else if (!seen.add(r.evidence())) {
        problems.add("signatures.reports: " + r.evidence() + " appears twice.");
      }
      if (modulusOk && (r.s() < 0 || r.s() >= s.n())) {
        problems.add("signatures.reports " + r.evidence() + ": s must be from 0 to n - 1.");
      }
    }
    if (!seen.containsAll(CryptoContent.SIGNED_EVIDENCE)) problems.add("signatures.reports: E03 and E04 are both required.");
  }

  private static void evidence(List<Evidence> evidence, List<String> problems) {
    if (evidence == null || evidence.size() != CryptoContent.EVIDENCE_IDS.size()) {
      problems.add("evidence: exactly E01, E02, E03 and E04 are required, in that order.");
      return;
    }
    for (int i = 0; i < evidence.size(); i++) {
      Evidence e = evidence.get(i);
      String id = CryptoContent.EVIDENCE_IDS.get(i);
      if (e == null || !id.equals(e.id())) {
        problems.add("evidence " + (i + 1) + ": id must be " + id + ".");
        continue;
      }
      if (e.text() == null || e.text().isBlank() || e.text().length() > MAX_TEXT) {
        problems.add("Evidence " + id + ": text is required (at most " + MAX_TEXT + " characters).");
      }
      boolean signed = CryptoContent.SIGNED_EVIDENCE.contains(id);
      boolean hasSigner = e.claimedSigner() != null && !e.claimedSigner().isBlank();
      if (signed && !hasSigner) problems.add("Evidence " + id + ": claimedSigner is required.");
      if (!signed && e.claimedSigner() != null) problems.add("Evidence " + id + ": claimedSigner is only for signed reports.");
    }
  }

  private static void suspects(CryptoContent content, List<String> problems) {
    List<String> suspects = content.suspects();
    if (suspects == null || suspects.size() != CryptoContent.SUSPECT_COUNT) {
      problems.add("suspects: exactly " + CryptoContent.SUSPECT_COUNT + " suspects are required.");
    }
    if (suspects != null) {
      Set<String> names = new HashSet<>();
      for (String name : suspects) {
        if (name == null || name.isBlank() || name.length() > MAX_NAME) {
          problems.add("suspects: every name is required (at most " + MAX_NAME + " characters).");
        } else if (!names.add(name)) {
          problems.add("suspects: " + name + " appears twice.");
        }
      }
    }
    if (content.correctSuspect() == null || suspects == null || !suspects.contains(content.correctSuspect())) {
      problems.add("correctSuspect: must be one of the six suspects.");
    }
  }

  private static void recovery(CryptoContent.Recovery recovery, List<String> problems) {
    if (recovery == null || recovery.question() == null || recovery.question().isBlank() || recovery.question().length() > MAX_TEXT) {
      problems.add("recovery.question: required (at most " + MAX_TEXT + " characters).");
      return;
    }
    List<String> answers = recovery.acceptedAnswers();
    if (answers == null || answers.isEmpty() || answers.size() > 20) {
      problems.add("recovery.acceptedAnswers: from 1 to 20 answers are required.");
      return;
    }
    for (String answer : answers) {
      if (answer == null || AnswerNormalizer.normalize(answer).isEmpty() || answer.length() > 500) {
        problems.add("recovery.acceptedAnswers: every answer must be filled in (at most 500 characters).");
        return;
      }
    }
  }

  static boolean prime(long value) {
    if (value < 2 || value >= MAX_MODULUS) return false;
    for (long d = 2; d * d <= value; d++) if (value % d == 0) return false;
    return true;
  }
}
