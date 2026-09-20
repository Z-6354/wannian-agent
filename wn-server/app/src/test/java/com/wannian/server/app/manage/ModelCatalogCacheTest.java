package com.wannian.server.app.manage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ModelCatalogCacheTest {

    @Test
    void expiresAfterTtl() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-19T00:00:00Z"));
        Clock clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        ModelCatalogCache cache = new ModelCatalogCache(Duration.ofSeconds(30), clock);
        cache.put("openai-main", List.of(new ModelCatalogEntry("a", "A")));

        assertThat(cache.get("openai-main")).isPresent();
        now.set(Instant.parse("2026-09-19T00:00:31Z"));
        assertThat(cache.get("openai-main")).isEmpty();
    }
}
