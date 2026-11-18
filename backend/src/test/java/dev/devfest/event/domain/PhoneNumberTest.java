package dev.devfest.event.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PhoneNumberTest {

  @Test
  void onlyTheDigitsCount() {
    assertEquals("97059881", PhoneNumber.digits("+970-59-881"));
    assertEquals("97059881", PhoneNumber.digits("970 59 881"));
    assertEquals("97059881", PhoneNumber.digits("97059881"));
    assertEquals("97059881", PhoneNumber.digits("(970) 59.881"));
    assertEquals("", PhoneNumber.digits("call me"));
    assertEquals("", PhoneNumber.digits(null));
  }

  @Test
  void differentWaysOfWritingTheSameNumberMatch() {
    assertTrue(PhoneNumber.matches("970 59 881", "+970-59-881"));
    assertTrue(PhoneNumber.matches("97059881", "+970-59-881"));
    assertTrue(PhoneNumber.matches("+970-59-881", "970 59 881"));
    assertTrue(PhoneNumber.matches(" 970\t59\n881 ", "+970-59-881"));
  }

  @Test
  void aDifferentNumberDoesNotMatch() {
    assertFalse(PhoneNumber.matches("970 59 882", "+970-59-881"));
    assertFalse(PhoneNumber.matches("970 59 88", "+970-59-881"));
    assertFalse(PhoneNumber.matches("0970 59 881", "+970-59-881"), "a leading digit is a different number");
  }

  @Test
  void anEmptyDigitsOnlyValueNeverMatches() {
    assertFalse(PhoneNumber.matches("", ""));
    assertFalse(PhoneNumber.matches("---", "+--"));
    assertFalse(PhoneNumber.matches("abc", ""));
    assertFalse(PhoneNumber.matches("970 59 881", ""), "a room without a number accepts nothing");
    assertFalse(PhoneNumber.matches("", "+970-59-881"));
    assertFalse(PhoneNumber.matches(null, "+970-59-881"));
    assertFalse(PhoneNumber.matches("970 59 881", null));
    assertFalse(PhoneNumber.isSet(" - "));
    assertTrue(PhoneNumber.isSet("+970"));
  }
}
