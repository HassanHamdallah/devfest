package dev.devfest.event.service;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/** One-time passwords for generated team accounts: 16 letters and digits from SecureRandom. */
@Component
public class TeamPasswordGenerator {

  public static final int LENGTH = 16;
  private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

  private final SecureRandom random = new SecureRandom();

  public String generate() {
    StringBuilder password = new StringBuilder(LENGTH);
    for (int i = 0; i < LENGTH; i++) password.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
    return password.toString();
  }
}
