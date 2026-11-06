package dev.devfest.event.domain;

import static dev.devfest.event.domain.TerminalTestPackages.untar;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.devfest.event.domain.TerminalTarWriter.Entry;
import dev.devfest.event.domain.TerminalTestPackages.TarMember;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class TerminalTarWriterTest {

  @Test
  void aBinaryFileComesBackByteForByteAtEverySizeAroundTheBlockBoundary() {
    Random random = new Random(42);
    for (int size : new int[] { 0, 1, 255, 256, 511, 512, 513, 1024, 70_000 }) {
      byte[] data = new byte[size];
      random.nextBytes(data);
      if (size >= 256) for (int i = 0; i < 256; i++) data[i] = (byte) i;
      List<TarMember> back = untar(TerminalTarWriter.gzip(List.of(Entry.file("srv/blob.bin", data, 0644, 0, 0, 1_700_000_000L))));
      assertEquals(1, back.size(), "size " + size);
      assertArrayEquals(data, back.get(0).data(), "size " + size);
      assertEquals(size, back.get(0).size());
    }
  }

  @Test
  void ownersModesTimesAndTypesAreWrittenNumerically() {
    List<TarMember> back = untar(
      TerminalTarWriter.gzip(
        List.of(
          Entry.directory("srv/rami", 0750, 2000, 2001, 1_600_000_000L),
          Entry.file("srv/rami/notes.txt", "hi\n".getBytes(), 0640, 2000, 2001, 1_700_000_123L),
          Entry.file("srv/root.txt", "r".getBytes(), 04755, 0, 0, 0L)
        )
      )
    );
    assertEquals(3, back.size());
    TarMember dir = back.get(0);
    assertEquals("srv/rami", dir.path());
    assertEquals('5', dir.type());
    assertEquals(0750, dir.mode());
    assertEquals(2000, dir.uid());
    assertEquals(2001, dir.gid());
    assertEquals(0, dir.size());
    TarMember file = back.get(1);
    assertEquals('0', file.type());
    assertEquals(0640, file.mode());
    assertEquals(1_700_000_123L, file.mtime());
    assertEquals("", file.uname(), "owners are numeric only");
    assertEquals(04755, back.get(2).mode());
  }

  @Test
  void longPathsUseThePrefixField() {
    String directory = ("d".repeat(30) + "/").repeat(3) + "e".repeat(30);
    String path = directory + "/" + "f".repeat(99);
    assertTrue(path.length() > 100 && path.length() <= 255, "length " + path.length());
    List<TarMember> back = untar(TerminalTarWriter.gzip(List.of(Entry.file(path, new byte[] { 7 }, 0644, 0, 0, 5L))));
    assertEquals(path, back.get(0).path());
    String[] parts = TerminalTarWriter.split(path, false);
    assertNotNull(parts);
    assertTrue(parts[1].length() <= 100 && parts[0].length() <= 155);
    assertEquals(path, parts[0] + "/" + parts[1]);
  }

  @Test
  void aLongDirectoryNameKeepsItsTrailingSlashInTheNameField() {
    String path = "a".repeat(60) + "/" + "b".repeat(60) + "/" + "c".repeat(60);
    List<TarMember> back = untar(TerminalTarWriter.gzip(List.of(Entry.directory(path, 0755, 0, 0, 1L))));
    assertEquals(path, back.get(0).path());
    assertEquals('5', back.get(0).type());
  }

  @Test
  void pathsThatCannotBeSplitAreReported() {
    assertNull(TerminalTarWriter.split("x".repeat(101), false), "one name over 100 bytes");
    assertNull(TerminalTarWriter.split("p".repeat(160) + "/" + "n".repeat(50), false), "prefix over 155 bytes");
    assertNull(TerminalTarWriter.split("a/".repeat(130) + "z", false), "over 255 bytes");
    assertThrows(
      IllegalArgumentException.class,
      () -> TerminalTarWriter.gzip(List.of(Entry.file("x".repeat(101), new byte[0], 0644, 0, 0, 0)))
    );
  }

  @Test
  void multiByteNamesAreSplitOnBytesNotCharacters() {
    String path = "é".repeat(40) + "/" + "ü".repeat(45);
    List<TarMember> back = untar(TerminalTarWriter.gzip(List.of(Entry.file(path, new byte[] { 1 }, 0644, 0, 0, 1L))));
    assertEquals(path, back.get(0).path());
  }

  @Test
  void theSameEntriesAlwaysGiveTheSameBytes() {
    List<Entry> entries = List.of(
      Entry.file("a/b.txt", "x".getBytes(), 0644, 2000, 2000, 99L),
      Entry.directory("a", 0755, 0, 0, 1L)
    );
    assertArrayEquals(TerminalTarWriter.gzip(entries), TerminalTarWriter.gzip(entries));
  }

  @Test
  void idsOutsideTheFieldAreRefused() {
    assertThrows(
      IllegalArgumentException.class,
      () -> TerminalTarWriter.gzip(List.of(Entry.file("a", new byte[0], 0644, 8_000_000, 0, 0)))
    );
  }

  @Test
  void aFolderEntryCarriesItsOwnModificationTime() {
    List<TarMember> back = untar(TerminalTarWriter.gzip(List.of(Entry.directory("srv/rami", 0750, 2000, 2001, 1_773_480_360L))));
    assertEquals(1_773_480_360L, back.get(0).mtime());
    assertEquals('5', back.get(0).type());
  }
}
