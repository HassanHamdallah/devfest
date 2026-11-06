package dev.devfest.event.service;

import dev.devfest.event.repository.TerminalBundleBytes;
import java.lang.ref.SoftReference;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * The most recently served archive of each room, held softly: sixteen teams starting their terminals at once
 * share one copy instead of each loading 20 MB from the database. An entry is used only when its digest equals
 * the one the database reports, so a replaced package is never served stale; the JVM drops the bytes under memory
 * pressure.
 */
@Component
class TerminalBundleCache {

  private final Map<Long, SoftReference<TerminalBundleBytes>> byRoom = new ConcurrentHashMap<>();

  /** The cached archive for {@code sha256}, else {@code load}'s result (which is then cached). */
  Optional<TerminalBundleBytes> get(long roomId, String sha256, Supplier<Optional<TerminalBundleBytes>> load) {
    SoftReference<TerminalBundleBytes> held = byRoom.get(roomId);
    TerminalBundleBytes cached = held == null ? null : held.get();
    if (cached != null && cached.sha256().equals(sha256)) return Optional.of(cached);
    Optional<TerminalBundleBytes> loaded = load.get();
    loaded.ifPresentOrElse(bytes -> byRoom.put(roomId, new SoftReference<>(bytes)), () -> byRoom.remove(roomId));
    return loaded;
  }

  void forget(long roomId) {
    byRoom.remove(roomId);
  }
}
