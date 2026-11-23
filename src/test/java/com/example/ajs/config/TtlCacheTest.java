package com.example.ajs.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the TTL cache that bounds IBM i load.
 *
 * <p>These exist because the failure mode is silent in the wrong direction. A TTL
 * that never expires turns a monitoring tool into a device that confidently
 * reports last hour's state; a TTL that always expires removes the load control
 * this was added for and nobody notices, because pages still render. Neither
 * shows up as an error, so both are pinned here.
 *
 * <p>Time is advanced by injecting a zero-length TTL where possible and by
 * sleeping only a few milliseconds where a real clock is unavoidable. The cache
 * uses {@link System#nanoTime()} deliberately (monotonic, immune to wall-clock
 * adjustments), so there is nothing to stub without changing the design.
 */
class TtlCacheTest {

    @Test
    @DisplayName("a stored value is served until its TTL elapses, then treated as absent")
    void valuesExpireAfterTtl() throws InterruptedException {
        // 1 second is the minimum TTL the constructor allows; wait past it.
        Cache cache = new TtlCache("test", 1);
        cache.put("k", "v");
        assertThat(cache.get("k")).isNotNull();
        assertThat(cache.get("k").get()).isEqualTo("v");

        Thread.sleep(1100);

        // Expired: a miss, not a stale hit.
        assertThat(cache.get("k")).isNull();
    }

    @Test
    @DisplayName("expiry is per key, so one stale entry does not discard the rest")
    void expiryIsPerEntry() throws InterruptedException {
        Cache cache = new TtlCache("test", 1);
        cache.put("old", "a");
        Thread.sleep(600);
        cache.put("new", "b");
        Thread.sleep(600); // "old" is now ~1.2s, "new" ~0.6s

        assertThat(cache.get("old")).isNull();
        assertThat(cache.get("new")).isNotNull();
    }

    @Test
    @DisplayName("an expired entry is reloaded through the loader rather than served")
    void expiredEntryReloadsViaLoader() throws InterruptedException {
        Cache cache = new TtlCache("test", 1);
        AtomicInteger loads = new AtomicInteger();

        String first = cache.get("k", () -> {
            loads.incrementAndGet();
            return "loaded";
        });
        assertThat(first).isEqualTo("loaded");

        // Within TTL: no second load.
        cache.get("k", () -> {
            loads.incrementAndGet();
            return "loaded";
        });
        assertThat(loads.get()).isEqualTo(1);

        Thread.sleep(1100);
        cache.get("k", () -> {
            loads.incrementAndGet();
            return "reloaded";
        });
        // The reload proves the expired value was not served from the map.
        assertThat(loads.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("evict and clear drop the recorded times, so nothing is resurrected")
    void evictAndClearForgetTimes() {
        Cache cache = new TtlCache("test", 60);
        cache.put("a", 1);
        cache.put("b", 2);

        cache.evict("a");
        assertThat(cache.get("a")).isNull();
        assertThat(cache.get("b")).isNotNull();

        cache.clear();
        assertThat(cache.get("b")).isNull();
    }

    @Test
    @DisplayName("putIfAbsent stamps only when it actually stores a value")
    void putIfAbsentDoesNotExtendTheLifeOfAnExistingEntry() throws InterruptedException {
        Cache cache = new TtlCache("test", 1);
        cache.put("k", "original");
        Thread.sleep(700);

        // A losing putIfAbsent must not reset the clock, or an entity that is
        // re-read on every request would never expire.
        Cache.ValueWrapper existing = cache.putIfAbsent("k", "usurper");
        assertThat(existing).isNotNull();
        assertThat(existing.get()).isEqualTo("original");

        Thread.sleep(500); // total ~1.2s since the original write
        assertThat(cache.get("k")).isNull();
    }

    @Test
    @DisplayName("the cache manager maps each cache name to the configured TTL rate")
    void managerUsesTwoRates() {
        CacheConfig config = new CacheConfig(900, 60);
        CacheConfig.AJSCacheStats stats = config.ajsCacheStats();

        // Inventory is long-lived; status lists are short. A single rate would
        // either make the failures page stale or re-scan the master file.
        assertThat(stats.ttlOf(CacheConfig.JOBS)).isEqualTo(900);
        assertThat(stats.ttlOf(CacheConfig.COMMANDS)).isEqualTo(900);
        assertThat(stats.ttlOf(CacheConfig.COMPLETION)).isEqualTo(60);
        assertThat(stats.ttlOf(CacheConfig.HELD)).isEqualTo(60);
        assertThat(stats.ttlOf(CacheConfig.NEVER_RUN)).isEqualTo(60);
    }

    @Test
    @DisplayName("the kill switch really stops caching, so no stale value can survive it")
    void disabledCacheNeverStores() {
        // This exists because the obvious control (spring.cache.type=none) does
        // not work: the @Primary CacheManager bean wins over the auto-configuration
        // that property drives. Verified by running the app with that property set
        // and watching the master file still be read once across repeated requests.
        CacheConfig config = new CacheConfig(900, 60);
        var manager = config.ajsCacheManager(false);
        Cache cache = manager.getCache(CacheConfig.JOBS);
        assertThat(cache).isNotNull();

        cache.put("all", List.of("PAYROLL1"));
        assertThat(cache.get("all")).isNull();

        AtomicInteger loads = new AtomicInteger();
        for (int i = 0; i < 3; i++) {
            cache.get("all", () -> {
                loads.incrementAndGet();
                return List.of("PAYROLL1");
            });
        }
        // Every read reached the loader: no caching at all.
        assertThat(loads.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("the enabled cache does store, so the kill switch is a real difference")
    void enabledCacheStores() {
        CacheConfig config = new CacheConfig(900, 60);
        Cache cache = config.ajsCacheManager(true).getCache(CacheConfig.JOBS);
        AtomicInteger loads = new AtomicInteger();
        for (int i = 0; i < 3; i++) {
            cache.get("all", () -> {
                loads.incrementAndGet();
                return List.of("PAYROLL1");
            });
        }
        assertThat(loads.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a job list round-trips through the cache unchanged")
    void cachesListsByReference() {
        Cache cache = new TtlCache("test", 60);
        List<String> jobs = List.of("PAYROLL1", "ARCHIVE1");
        cache.put("all", jobs);

        Object got = cache.get("all").get();
        // The repository returns immutable lists, so sharing the reference is
        // safe and avoids copying a few thousand objects per page view.
        assertThat(got).isSameAs(jobs);
    }
}