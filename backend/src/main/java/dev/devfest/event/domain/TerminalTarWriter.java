package dev.devfest.event.domain;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * A small writer for gzip-compressed POSIX ustar archives: the format BusyBox {@code tar} reads inside the
 * terminal's Linux. File bytes are copied exactly (binary files included), owners are numeric uid/gid only (the
 * name fields stay empty), and paths longer than 100 bytes use the 155-byte prefix field. The same entries always
 * produce the same bytes, so the archive's SHA-256 identifies the package.
 */
public final class TerminalTarWriter {

  private static final int BLOCK = 512;
  private static final int NAME_BYTES = 100;
  private static final int PREFIX_BYTES = 155;
  /** Largest value an 11-digit octal field holds (size and mtime). */
  private static final long OCTAL_11_MAX = 077_777_777_777L;
  private static final long OCTAL_7_MAX = 07_777_777L;

  /** One archive member. {@code path} has no leading or trailing slash; {@code data} is null for a directory. */
  public record Entry(String path, boolean directory, int mode, int uid, int gid, long mtimeSeconds, byte[] data) {
    public static Entry file(String path, byte[] data, int mode, int uid, int gid, long mtimeSeconds) {
      return new Entry(path, false, mode, uid, gid, mtimeSeconds, data);
    }

    public static Entry directory(String path, int mode, int uid, int gid, long mtimeSeconds) {
      return new Entry(path, true, mode, uid, gid, mtimeSeconds, null);
    }
  }

  private TerminalTarWriter() {}

  /** The gzip-compressed archive of {@code entries}, in the given order. */
  public static byte[] gzip(List<Entry> entries) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (GZIPOutputStream out = new GZIPOutputStream(bytes)) {
      for (Entry entry : entries) {
        out.write(header(entry));
        if (!entry.directory() && entry.data().length > 0) {
          out.write(entry.data());
          out.write(new byte[(BLOCK - entry.data().length % BLOCK) % BLOCK]);
        }
      }
      out.write(new byte[2 * BLOCK]);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return bytes.toByteArray();
  }

  /**
   * Splits a path into the ustar {@code prefix} and {@code name} fields ({@code [prefix, name]}, prefix empty when
   * the whole name fits 100 bytes); null when no split fits. A directory's name carries its trailing slash.
   */
  public static String[] split(String path, boolean directory) {
    byte[] full = (directory ? path + "/" : path).getBytes(StandardCharsets.UTF_8);
    if (full.length <= NAME_BYTES) return new String[] { "", new String(full, StandardCharsets.UTF_8) };
    for (int slash = 0; slash < full.length - 1; slash++) {
      if (full[slash] != '/') continue;
      int nameLength = full.length - slash - 1;
      if (nameLength > NAME_BYTES) continue;
      if (slash > PREFIX_BYTES) return null;
      return new String[] {
        new String(full, 0, slash, StandardCharsets.UTF_8),
        new String(full, slash + 1, nameLength, StandardCharsets.UTF_8),
      };
    }
    return null;
  }

  private static byte[] header(Entry entry) {
    String[] parts = split(entry.path(), entry.directory());
    if (parts == null) throw new IllegalArgumentException("Path too long for ustar: " + entry.path());
    long size = entry.directory() ? 0 : entry.data().length;
    if (size > OCTAL_11_MAX) throw new IllegalArgumentException("File too large for ustar: " + entry.path());
    byte[] block = new byte[BLOCK];
    put(block, 0, NAME_BYTES, parts[1]);
    octal(block, 100, 8, entry.mode() & 07777, OCTAL_7_MAX);
    octal(block, 108, 8, entry.uid(), OCTAL_7_MAX);
    octal(block, 116, 8, entry.gid(), OCTAL_7_MAX);
    octal(block, 124, 12, size, OCTAL_11_MAX);
    octal(block, 136, 12, Math.min(Math.max(entry.mtimeSeconds(), 0), OCTAL_11_MAX), OCTAL_11_MAX);
    block[156] = (byte) (entry.directory() ? '5' : '0');
    put(block, 257, 6, "ustar\0");
    put(block, 263, 2, "00");
    // uname (265, 32) and gname (297, 32) stay empty: tar then uses the numeric ids.
    put(block, 345, PREFIX_BYTES, parts[0]);
    // The checksum is computed with its own field read as eight spaces.
    for (int i = 148; i < 156; i++) block[i] = ' ';
    long sum = 0;
    for (byte b : block) sum += b & 0xff;
    byte[] digits = String.format("%06o", sum).getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(digits, 0, block, 148, 6);
    block[154] = 0;
    block[155] = ' ';
    return block;
  }

  private static void put(byte[] block, int offset, int length, String text) {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > length) throw new IllegalArgumentException("Field too long: " + text);
    System.arraycopy(bytes, 0, block, offset, bytes.length);
  }

  /** Zero-padded octal digits followed by a NUL, filling {@code length} bytes. */
  private static void octal(byte[] block, int offset, int length, long value, long max) {
    if (value < 0 || value > max) throw new IllegalArgumentException("Value out of range for a ustar field: " + value);
    String digits = String.format("%0" + (length - 1) + "o", value);
    put(block, offset, length - 1, digits);
    block[offset + length - 1] = 0;
  }
}
