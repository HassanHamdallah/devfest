package dev.devfest.event.domain.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.devfest.event.domain.EventRuleException;
import dev.devfest.event.domain.recovery.ArduinoCheck.Run;
import dev.devfest.event.domain.recovery.ArduinoCheck.TestResult;
import dev.devfest.event.domain.recovery.RecoveryLevels.Arduino;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** The shipped Arduino program: each bug alone, every accepted variant, the slot's options, the outside-change rule. */
class ArduinoCheckTest {

  private static final Arduino PROGRAM = ShippedRecovery.levels().arduino();
  private static final String RIGHT = "B";
  private static final List<String> ALL_TESTS = List.of("T1", "T2", "T3", "T4", "T5", "T6", "T7", "T8");

  /** Every bug fixed with its first variant; the slot is chosen separately. */
  private static List<String> fixedLines() {
    List<String> lines = ShippedRecovery.originalLines();
    lines.set(39, "    authorized = false;");
    lines.set(44, "  if (doorOpen && !authorized) {");
    lines.set(49, "  // if (!doorOpen) {");
    lines.set(50, "  //   alarmActive = false;");
    lines.set(51, "  // }");
    lines.set(55, "  if (motionDetected && doorOpen) {");
    lines.set(74, "  authorized = false;");
    return lines;
  }

  private static Set<String> passing(Run run) {
    return run.tests().stream().filter(TestResult::passed).map(TestResult::id).collect(Collectors.toSet());
  }

  private static List<String> with(List<String> base, Consumer<List<String>> change) {
    List<String> lines = new ArrayList<>(base);
    change.accept(lines);
    return lines;
  }

  @Test
  void theShippedProgramKeepsItsBugLinesAndTheAddedGlobal() {
    List<String> lines = ShippedRecovery.originalLines();
    assertEquals(89, lines.size());
    assertEquals("int motionEvents = 0;", lines.get(12));
    assertEquals("bool entered = false;", lines.get(13));
    assertEquals(88, PROGRAM.slot().afterLine());
    assertEquals(List.of("T1", "T2", "T3", "T4", "T5", "T6", "T7", "T8"), PROGRAM.tests().stream().map(RecoveryLevels.Test::id).toList());
  }

  @Test
  void theUntouchedProgramPassesOnlyTheTestWithoutBugs() {
    Run run = ArduinoCheck.run(PROGRAM, ShippedRecovery.originalLines(), "A");
    assertFalse(run.outsideChanged());
    assertEquals(Set.of("T1"), passing(run));
    assertFalse(run.passed());
  }

  @Test
  void everyBugFixedAndTheRightOptionPassEveryTest() {
    Run run = ArduinoCheck.run(PROGRAM, fixedLines(), RIGHT);
    assertEquals(Set.copyOf(ALL_TESTS), passing(run));
    assertTrue(run.passed());
    assertEquals(
      List.of(
        "Authorization starts correctly",
        "Authorization expires after 5 seconds",
        "Unauthorized door opening triggers alarm",
        "Authorized opening does not trigger alarm",
        "Motion counts only while door is open",
        "Reset clears authorization",
        "Authorization ends after valid entry",
        "Alarm stays on until reset"
      ),
      run.tests().stream().map(TestResult::name).toList()
    );
  }

  @Test
  void eachBugAloneTurnsOnlyItsOwnTests() {
    List<String> original = ShippedRecovery.originalLines();
    Map<String, Consumer<List<String>>> fixes = Map.of(
      "B1", l -> l.set(39, "authorized = false;"),
      "B2", l -> l.set(44, "if (doorOpen && !authorized) {"),
      "B3", l -> {
        l.set(49, "");
        l.set(50, "");
        l.set(51, "");
      },
      "B4", l -> l.set(55, "if (doorOpen && motionDetected) {"),
      "B5", l -> l.set(74, "authorized = false;")
    );
    Map<String, Set<String>> expected = Map.of(
      "B1", Set.of("T1", "T2"),
      "B2", Set.of("T1", "T3", "T4"),
      "B3", Set.of("T1", "T8"),
      "B4", Set.of("T1", "T5"),
      "B5", Set.of("T1", "T6")
    );
    fixes.forEach((bug, fix) -> assertEquals(expected.get(bug), passing(ArduinoCheck.run(PROGRAM, with(original, fix), "A")), bug));
    assertEquals(Set.of("T1", "T7"), passing(ArduinoCheck.run(PROGRAM, original, RIGHT)), "B6, the slot, alone");
  }

  @Test
  void everyAcceptedVariantFixesItsBugWithAnySpacing() {
    List<String> fixed = fixedLines();
    for (String b2 : List.of("if(doorOpen&&!authorized){", "if (doorOpen && authorized == false) {", "if (!authorized && doorOpen) {", "  if ( authorized==false && doorOpen )  {  ")) {
      assertTrue(ArduinoCheck.run(PROGRAM, with(fixed, l -> l.set(44, b2)), RIGHT).passed(), b2);
    }
    for (String b4 : List.of("if(motionDetected&&doorOpen){", "if (doorOpen && motionDetected) {")) {
      assertTrue(ArduinoCheck.run(PROGRAM, with(fixed, l -> l.set(55, b4)), RIGHT).passed(), b4);
    }
    for (String b1 : List.of("authorized=false;", "\tauthorized   =   false ;")) {
      assertTrue(ArduinoCheck.run(PROGRAM, with(fixed, l -> l.set(39, b1)), RIGHT).passed(), b1);
    }
    assertTrue(ArduinoCheck.run(PROGRAM, with(fixed, l -> l.set(49, "//")), RIGHT).passed(), "an empty comment");
  }

  @Test
  void aWrongFixIsNotAccepted() {
    List<String> fixed = fixedLines();
    assertFalse(passing(ArduinoCheck.run(PROGRAM, with(fixed, l -> l.set(44, "if (doorOpen && authorized != true) {")), RIGHT)).contains("T3"), "not a listed variant");
    assertFalse(passing(ArduinoCheck.run(PROGRAM, with(fixed, l -> l.set(44, "if (doorOpen || !authorized) {")), RIGHT)).contains("T4"));
    assertFalse(passing(ArduinoCheck.run(PROGRAM, with(fixed, l -> l.set(39, "authorized = false; // done")), RIGHT)).contains("T2"));
    assertFalse(passing(ArduinoCheck.run(PROGRAM, with(fixed, l -> l.set(50, "alarmActive = false;")), RIGHT)).contains("T8"), "one B3 line still active");
    assertFalse(passing(ArduinoCheck.run(PROGRAM, with(fixed, l -> l.set(50, "/* alarmActive = false; */")), RIGHT)).contains("T8"), "only // comments");
  }

  @Test
  void onlyOptionBEndsTheWindowAfterAValidEntry() {
    for (String option : List.of("A", "C", "D")) {
      Run run = ArduinoCheck.run(PROGRAM, fixedLines(), option);
      assertEquals(Set.of("T1", "T2", "T3", "T4", "T5", "T6", "T8"), passing(run), option);
      assertFalse(run.passed(), option);
    }
    assertTrue(PROGRAM.slot().options().getFirst().code().startsWith("if (!doorOpen && authorized)"), "A ends the window before the door ever opens");
  }

  @Test
  void aChangedLineOutsideTheBugsFailsEveryTest() {
    Run edited = ArduinoCheck.run(PROGRAM, with(fixedLines(), l -> l.set(31, "    authorized = false;")), RIGHT);
    assertTrue(edited.outsideChanged());
    assertEquals(Set.of(), passing(edited));
    assertEquals(8, edited.tests().size());
    Run emptied = ArduinoCheck.run(PROGRAM, with(fixedLines(), l -> l.set(0, "")), RIGHT);
    assertTrue(emptied.outsideChanged(), "removing a line outside the bugs is a change too");
    Run commented = ArduinoCheck.run(PROGRAM, with(fixedLines(), l -> l.set(13, "// bool entered = false;")), RIGHT);
    assertTrue(commented.outsideChanged());
  }

  @Test
  void whitespaceOutsideTheBugsIsIgnored() {
    List<String> reformatted = with(fixedLines(), l -> {
      l.set(0, "const   int doorPin=2;");
      l.set(16, "\t\tpinMode(doorPin,INPUT);");
      l.set(5, "   ");
    });
    Run run = ArduinoCheck.run(PROGRAM, reformatted, RIGHT);
    assertFalse(run.outsideChanged());
    assertTrue(run.passed());
  }

  @Test
  void aMalformedRunIsRefused() {
    assertThrows(EventRuleException.class, () -> ArduinoCheck.run(PROGRAM, fixedLines().subList(0, 88), RIGHT), "a line missing");
    assertThrows(EventRuleException.class, () -> ArduinoCheck.run(PROGRAM, with(fixedLines(), l -> l.add("")), RIGHT), "a line added");
    assertThrows(EventRuleException.class, () -> ArduinoCheck.run(PROGRAM, with(fixedLines(), l -> l.set(3, "a\nb")), RIGHT));
    assertThrows(EventRuleException.class, () -> ArduinoCheck.run(PROGRAM, with(fixedLines(), l -> l.set(3, "x".repeat(301))), RIGHT));
    assertThrows(EventRuleException.class, () -> ArduinoCheck.run(PROGRAM, fixedLines(), "E"));
    assertThrows(EventRuleException.class, () -> ArduinoCheck.run(PROGRAM, fixedLines(), null));
    assertThrows(EventRuleException.class, () -> ArduinoCheck.run(PROGRAM, null, RIGHT));
  }
}
