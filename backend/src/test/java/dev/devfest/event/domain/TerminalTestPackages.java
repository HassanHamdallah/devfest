package dev.devfest.event.domain;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Test helpers: builds ZIP packages and reads the gzip/ustar bundles back with plain Java. */
public final class TerminalTestPackages {

  private TerminalTestPackages() {}

  /** A ZIP whose entries appear in insertion order; a name ending in "/" is a folder (its bytes are ignored). */
  public static byte[] zip(Map<String, byte[]> entries) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream out = new ZipOutputStream(bytes)) {
      for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
        ZipEntry zipEntry = new ZipEntry(entry.getKey());
        zipEntry.setTimeLocal(LocalDateTime.of(2026, 3, 14, 9, 30, 0));
        out.putNextEntry(zipEntry);
        if (!entry.getKey().endsWith("/")) out.write(entry.getValue());
        out.closeEntry();
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return bytes.toByteArray();
  }

  public static Map<String, byte[]> files(String... pathsAndTexts) {
    Map<String, byte[]> map = new LinkedHashMap<>();
    for (int i = 0; i < pathsAndTexts.length; i += 2) {
      map.put(pathsAndTexts[i], pathsAndTexts[i + 1].getBytes(StandardCharsets.UTF_8));
    }
    return map;
  }

  public static byte[] text(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  /** One member of a ustar archive as a reader sees it. */
  public record TarMember(String path, char type, int mode, int uid, int gid, long size, long mtime, String uname, byte[] data) {}

  /** Reads a gzip(ustar) archive, verifying every header checksum and the two terminating zero blocks. */
  public static List<TarMember> untar(byte[] gzip) {
    try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gzip))) {
      byte[] tar = in.readAllBytes();
      List<TarMember> members = new ArrayList<>();
      int at = 0;
      while (true) {
        byte[] header = java.util.Arrays.copyOfRange(tar, at, at + 512);
        if (isZero(header)) {
          if (!isZero(java.util.Arrays.copyOfRange(tar, at + 512, at + 1024))) throw new IllegalStateException("one zero block only");
          if (at + 1024 != tar.length) throw new IllegalStateException("data after the end marker");
          return members;
        }
        long stored = Long.parseLong(field(header, 148, 6).trim(), 8);
        long sum = 0;
        for (int i = 0; i < 512; i++) sum += (i >= 148 && i < 156) ? ' ' : header[i] & 0xff;
        if (stored != sum) throw new IllegalStateException("bad checksum");
        if (!field(header, 257, 6).equals("ustar")) throw new IllegalStateException("bad magic");
        String name = field(header, 0, 100);
        String prefix = field(header, 345, 155);
        String path = prefix.isEmpty() ? name : prefix + "/" + name;
        long size = Long.parseLong(field(header, 124, 11), 8);
        char type = (char) header[156];
        if (type == '5' && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        at += 512;
        byte[] data = java.util.Arrays.copyOfRange(tar, at, at + (int) size);
        at += (int) ((size + 511) / 512 * 512);
        members.add(
          new TarMember(
            path,
            type,
            Integer.parseInt(field(header, 100, 7), 8),
            Integer.parseInt(field(header, 108, 7), 8),
            Integer.parseInt(field(header, 116, 7), 8),
            size,
            Long.parseLong(field(header, 136, 11), 8),
            field(header, 265, 32),
            data
          )
        );
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static boolean isZero(byte[] block) {
    for (byte b : block) if (b != 0) return false;
    return true;
  }

  private static String field(byte[] header, int offset, int length) {
    int end = offset;
    while (end < offset + length && header[end] != 0) end++;
    return new String(header, offset, end - offset, StandardCharsets.UTF_8);
  }
}
