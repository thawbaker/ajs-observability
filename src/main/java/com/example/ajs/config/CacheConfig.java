package com.example.ajs.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * Caching for the read-only AJS queries, and the thing that gives
 * {@code ajs.polling.*} in {@code application.yml} an effect.
 *
 * <h2>Why this exists</h2>
 * {@code req.txt} §6 asks for "cache slow-changing inventory; only poll what the
 * page needs", and risk R6 warns that excessive reading can consume IBM i
 * jobs/CPU. Two shapes of load were unaddressed:
 *
 * <ol>
 *   <li><b>Refresh hammering.</b> Load was exactly proportional to human page
 *       views with no rate limit, so a user holding F5 issued a full master-file
 *       scan per keypress. That is the one load path an operator controls and can
 *       get wrong at 3am.</li>
 *   <li><b>Duplicate reads within one page view.</b> The failures page called
 *       {@code findByCompletionCode("A")} twice — once for genuine failures, once
 *       for the {@code *NOSBM} skips — and the overview/job-detail paths each
 *       re-read the master file. Caching at the repository removes those.</li>
 * </ol>
 *
 * <h2>Why a TTL and not a background poller</h2>
 * The alternative is what C6 in NOTES.md recommends for live views: one scheduled
 * poller refreshing a cache on a timer. That is the right shape for a view that
 * must be current without a user asking, and the wrong shape here: it costs IBM i
 * work even when nobody is looking, which is precisely what this application
 * should never do. A TTL cache is demand-driven — no users, no queries — while
 * still capping the cost of a burst.
 *
 * <h2>Why ConcurrentMapCacheManager and not Caffeine/EhCache</h2>
 * The working set is a handful of entries: one list of job definitions (a few
 * thousand objects at the stated scale), two completion-code lists, one held list,
 * one never-run list, and one entry per job whose detail page was opened. A map
 * is sufficient, needs no extra dependency, and cannot evict the one entry that
 * matters under memory pressure — where an unbounded-size cache would. If the job
 * count grows far past {@code req.txt}'s assumption, this is the class to replace,
 * and the entry counts in {@link #AJSCacheStats} are the trigger to watch.
 *
 * <p>TTLs come from the existing properties. {@code inventory-seconds} governs the
 * job master file (definitions change when someone edits AJS, not second to
 * second); {@code live-seconds} governs completion/status lists, which move when
 * jobs finish. Commands are tied to inventory because they are part of a job
 * definition. Defaults match {@code application.yml}: 900s and 60s.
 *
 * <h2>How to turn it off</h2>
 * Set {@code ajs.polling.cache-enabled=false}. That is deliberate and explicit
 * because Spring Boot's own {@code spring.cache.type=none} does <em>not</em> work
 * here: this class declares a {@code @Primary} {@link CacheManager}, which wins
 * over the auto-configuration that property controls. That was found by trying it:
 * the cache stayed on and the log still showed a single master-file read across
 * repeated requests. An operator debugging "why is this number stale?" would reach
 * for the standard property, believe the cache was off, and be wrong. So the
 * switch that actually works is named here and tested.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /** Stable cache names, referenced by {@code @Cacheable} on the repository. */
    public static final String JOBS = "ajsJobs";
    public static final String COMPLETION = "ajsCompletion";
    public static final String NEVER_RUN = "ajsNeverRun";
    public static final String HELD = "ajsHeld";
    public static final String COMMANDS = "ajsCommands";

    /**
     * Per-date calendar results.
     *
     * <p>Separate from {@link #JOBS} because the key is a date. The repository
     * cannot rely on {@code findAllJobs()}'s cache from this call path: the
     * service invokes {@code findScheduledOn}, which calls {@code findAllJobs()}
     * by self-invocation, and Spring's proxy is bypassed — measured as five
     * master-file reads for five calendar dates when one was expected. Caching the
     * filtered result per date is both correct and cheaper than re-filtering a
     * cached master list on every view.
     */
    public static final String SCHEDULED = "ajsScheduled";

    private final long inventorySeconds;
    private final long liveSeconds;

    public CacheConfig(
            @Value("${ajs.polling.inventory-seconds:900}") long inventorySeconds,
            @Value("${ajs.polling.live-seconds:60}") long liveSeconds) {
        this.inventorySeconds = inventorySeconds;
        this.liveSeconds = liveSeconds;
    }

    /**
     * A {@link ConcurrentMapCacheManager} whose caches expire by age.
     *
     * <p>Spring's {@code ConcurrentMapCache} has no TTL support, and adding
     * Caffeine for six entries would be a dependency for one feature. So each
     * cache is a subclass that records the write time and treats the entry as
     * absent once it is older than its TTL. That is a read-time expiry: an entry
     * lingers in the map until it is either asked for again or overwritten, which
     * for six entries of a few thousand objects is acceptable and far simpler than
     * a scheduled sweeper — and a sweeper would be the background timer this class
     * exists to avoid.
     *
     * <p>Entries are immutable lists produced by the repository mappers, so there
     * is no risk of a caller mutating a cached value.
     */
    @Bean
    @Primary
    public CacheManager ajsCacheManager(
            @Value("${ajs.polling.cache-enabled:true}") boolean cacheEnabled) {
        ConcurrentMapCacheManager manager = new ConcurrentMapCacheManager(
                JOBS, COMPLETION, NEVER_RUN, HELD, COMMANDS, SCHEDULED) {
            @Override
            protected Cache createConcurrentMapCache(String name) {
                return cacheEnabled
                        ? new TtlCache(name, ttlSecondsFor(name))
                        : new NoOpCache(name);
            }
        };
        manager.setAllowNullValues(false);
        return manager;
    }

    private long ttlSecondsFor(String name) {
        // Status lists move when a job finishes; definitions move when someone
        // edits AJS. Two rates, not one, because a single TTL would either make
        // the failures page stale or re-read the master file too often.
        //
        // The per-date calendar cache takes the LIVE rate, not the inventory one:
        // a job that ran changes its next-run date, so a 15-minute-old answer to
        // "what is due on the 21st" can be wrong in a way that matters.
        return switch (name) {
            case COMPLETION, HELD, NEVER_RUN, SCHEDULED -> liveSeconds;
            default -> inventorySeconds;
        };
    }

    /**
     * A cache that never stores anything, used when
     * {@code ajs.polling.cache-enabled=false}.
     *
     * <p>Not the same as a very long TTL: this guarantees every read reaches the
     * database, so disabling the cache cannot leave a stale value in place. The
     * point of the switch is to make "no caching" unambiguous.
     */
    private static final class NoOpCache extends ConcurrentMapCache {
        NoOpCache(String name) {
            super(name, false);
        }

        @Override
        public ValueWrapper get(Object key) {
            return null;
        }

        @Override
        public <T> T get(Object key, Class<T> type) {
            return null;
        }

        @Override
        public <T> T get(Object key, Callable<T> valueLoader) {
            // The interface declares no checked exception: Spring's contract is
            // that a loader failure surfaces as Cache.ValueRetrievalException.
            // Run the loader and return without storing.
            try {
                return valueLoader.call();
            } catch (Exception ex) {
                throw new org.springframework.cache.Cache.ValueRetrievalException(
                        key, valueLoader, ex);
            }
        }

        @Override
        public void put(Object key, Object value) {
            // discard
        }

        @Override
        public ValueWrapper putIfAbsent(Object key, Object value) {
            return null;
        }

        @Override
        public void evict(Object key) {
            // nothing stored
        }

        @Override
        public void clear() {
            // nothing stored
        }
    }

    /** Names and TTLs, exposed for the Actuator info endpoint and for tests. */
    @Bean
    public AJSCacheStats ajsCacheStats() {
        return new AJSCacheStats(
                inventorySeconds,
                liveSeconds,
                List.of(JOBS, COMMANDS),
                List.of(COMPLETION, NEVER_RUN, HELD, SCHEDULED));
    }

    /**
     * The effective settings, surfaced so an operator can confirm what the
     * deployed build is actually using. Config that looks like a throttle but has
     * no effect is worse than no config, so the resolved values are reported
     * rather than assumed.
     */
    public record AJSCacheStats(
            long inventorySeconds,
            long liveSeconds,
            List<String> inventoryCaches,
            List<String> liveCaches) {

        public long ttlOf(String cacheName) {
            return inventoryCaches.contains(cacheName) ? inventorySeconds : liveSeconds;
        }
    }
}