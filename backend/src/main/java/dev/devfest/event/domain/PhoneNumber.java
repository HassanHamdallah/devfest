package dev.devfest.event.domain;

/**
 * The phone number answer of a points-and-phone room (rules §21.3): two numbers are the same when their DIGITS are, so
 * "+970-59-881", "970 59 881" and "97059881" match. A value without any digit never matches anything. Pure.
 */
public final class PhoneNumber {

  public static final int MAX_CHARACTERS = 40;

  private PhoneNumber() {}

  /** Only the characters 0-9 (ASCII), in order; null counts as empty. */
  public static String digits(String value) {
    if (value == null) return "";
    StringBuilder digits = new StringBuilder();
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c >= '0' && c <= '9') digits.append(c);
    }
    return digits.toString();
  }

  /** True when the room has a number that can be matched: at least one digit. */
  public static boolean isSet(String expected) {
    return !digits(expected).isEmpty();
  }

  public static boolean matches(String given, String expected) {
    String wanted = digits(expected);
    return !wanted.isEmpty() && wanted.equals(digits(given));
  }
}
