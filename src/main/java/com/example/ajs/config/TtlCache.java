package com.example.ajs.config;

import org.springframework.cache.concurrent.ConcurrentMapCache;

import java.util.concurrent.TimeUnit;

/**
 * A {@link ConcurrentMapCache} that treats entries older than a fixed age as
 * absent.
 *
 * <h2>Why not a scheduled sweeper</h2>
 * Expiry is resolved on read. A sweeper thread would be a background timer, and
 * the whole point of this cache is that the application does no work when nobody
 * is looking. Read-time expiry means an idle process is completely idle.
 *
 * <h2>Why the store is separate from the cache</h2>
 * {@code ConcurrentMapCache} owns an internal {@code ConcurrentHashMap} keyed by
 * the cache key. Overriding {@code get} to consult a parallel map of write times
 * keeps the parent's own lookup, eviction and {@code putIfAbsent} semantics intact
 * rather than reimplementing them, which is the usual way a hand-rolled TTL cache
 * grows a subtle concurrency bug.
 *
 * <h2>Known and accepted imprecision</h2>
 * Expiry is decided at read time, so two readers racing just after the deadline
 * can both miss and both reload — the cache is a load reducer, not a latch. That
 * is correct behaviour for this application: a double read wastes one query,
 * whereas blocking a request to prevent it would add latency to the page.
 */
class TtlCache extends ConcurrentMapCache {

    private final long ttlNanos;

    /** Key to the {@link System#nanoTime()} at which it was stored. */
    private final java.util.concurrent.ConcurrentMap<Object, Long> writtenAt =
            new java.util.concurrent.ConcurrentHashMap<>();

    TtlCache(String name, long ttlSeconds) {
        super(name, false);
        // A zero or negative TTL would make every read a miss while still paying
        // the bookkeeping; treat it as "caching disabled" is the caller's job via
        // configuration, so guard against a negative slipping through.
        this.ttlNanos = TimeUnit.SECONDS.toNanos(Math.max(1, ttlSeconds));
    }

    @Override
    public ValueWrapper get(Object key) {
        if (isExpired(key)) {
            evict(key);
            return null;
        }
        return super.get(key);
    }

    @Override
    public <T> T get(Object key, Class<T> type) {
        if (isExpired(key)) {
            evict(key);
            return null;
        }
        return super.get(key, type);
    }

    @Override
    public <T> T get(Object key, java.util.concurrent.Callable<T> valueLoader) {
        // Delegate to the parent so the loader runs under its per-key sync, then
        // stamp the time. Doing the expiry check first means an expired entry is
        // not served from the load path either.
        if (isExpired(key)) {
            evict(key);
        }
        T value = super.get(key, valueLoader);
        writtenAt.putIfAbsent(key, System.nanoTime());
        return value;
    }

    @Override
    public void put(Object key, Object value) {
        super.put(key, value);
        writtenAt.put(key, System.nanoTime());
    }

    @Override
    public ValueWrapper putIfAbsent(Object key, Object value) {
        ValueWrapper existing = super.putIfAbsent(key, value);
        // Only stamp when this call actually stored the value.
        if (existing == null) {
            writtenAt.put(key, System.nanoTime());
        }
        return existing;
    }

    @Override
    public void evict(Object key) {
        super.evict(key);
        writtenAt.remove(key);
    }

    @Override
    public void clear() {
        super.clear();
        writtenAt.clear();
    }

    private boolean isExpired(Object key) {
        Long at = writtenAt.get(key);
        if (at == null) {
            // A key present in the store but absent here can only happen if the
            // parent was cleared by another path; treat it as expired so it is
            // reloaded rather than served forever.
            return super.get(key) != null;
        }
        return System.nanoTime() - at > ttlNanos;
    }
}