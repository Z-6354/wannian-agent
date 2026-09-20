package com.wannian.server.app.manage;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 供应商目录短缓存，只在进程内存里。检索结果不写数据库。
 */
@Component
public class ModelCatalogCache {

    private static final Duration DEFAULT_TTL = Duration.ofSeconds(60);

    private final ConcurrentHashMap<String, Cached> byVendorId = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final Clock clock;

    public ModelCatalogCache() {
        this(DEFAULT_TTL, Clock.systemUTC());
    }

    ModelCatalogCache(Duration ttl, Clock clock) {
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void put(String vendorId, List<ModelCatalogEntry> entries) {
        if (vendorId == null || entries == null) {
            return;
        }
        byVendorId.put(vendorId, new Cached(clock.instant().plus(ttl), List.copyOf(entries)));
    }

    public Optional<List<ModelCatalogEntry>> get(String vendorId) {
        if (vendorId == null) {
            return Optional.empty();
        }
        Cached cached = byVendorId.get(vendorId);
        if (cached == null) {
            return Optional.empty();
        }
        if (clock.instant().isAfter(cached.expiresAt())) {
            byVendorId.remove(vendorId, cached);
            return Optional.empty();
        }
        return Optional.of(cached.entries());
    }

    public void invalidate(String vendorId) {
        if (vendorId != null) {
            byVendorId.remove(vendorId);
        }
    }

    private record Cached(Instant expiresAt, List<ModelCatalogEntry> entries) {}
}
