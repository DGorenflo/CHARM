package charm.bootstrap.semantics.pipeline;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Per-model client-side rate limiter enforcing a maximum number of requests per minute.
 *
 * <p>Requests are spaced evenly ("smooth" limiting) rather than allowed in bursts: with a
 * limit of 100/min each caller is handed a slot 600ms after the previous one. This keeps the
 * real request rate below the endpoint's limit even when many worker threads call
 * concurrently, avoiding HTTP 429 responses.
 *
 * <p>The known per-model limits are configured in {@link #LIMITS_RPM}. A safety margin
 * ({@link #SAFETY_FACTOR}) is applied so we stay comfortably under the server-side window,
 * which may count requests differently than our local clock. Models without a configured
 * limit are not throttled.
 */
public final class ModelRateLimiter {

    private static final Logger LOG = Logger.getLogger(ModelRateLimiter.class.getName());

    // Documented endpoint limits (requests per minute).
    private static final Map<String, Integer> LIMITS_RPM = Map.of(
        "mistralai--mistral-small-instruct", 100,
        "mistralai--mistral-small", 500,
        "nvidia--llama-3.2-nv-embedqa-1b", 138,
        "text-embedding-3-large" ,138 
    );

    // Stay a bit under the documented limit to absorb clock skew and server-side windowing.
    private static final double SAFETY_FACTOR = 0.9;

    // modelName -> limiter. Only models present in LIMITS_RPM get an entry.
    private static final Map<String, ModelRateLimiter> LIMITERS = new ConcurrentHashMap<>();

    private final long   intervalNanos;
    // Timestamp (nanoTime) of the next free slot. Advanced atomically by each acquirer.
    private final AtomicLong nextSlotNanos = new AtomicLong(System.nanoTime());

    private ModelRateLimiter(int effectiveRpm) {
        this.intervalNanos = 60_000_000_000L / effectiveRpm;
    }

    /**
     * Blocks until it is safe to issue a request for {@code modelName} without exceeding the
     * configured rate. Models without a known limit return immediately.
     */
    public static void acquire(String modelName) throws InterruptedException {
        if (modelName == null) return;
        Integer rpm = LIMITS_RPM.get(modelName);
        if (rpm == null) return; // unknown model — not throttled

        ModelRateLimiter limiter = LIMITERS.computeIfAbsent(modelName, m -> {
            int effective = Math.max(1, (int) Math.floor(rpm * SAFETY_FACTOR));
            LOG.info("Rate limiter for " + m + ": " + effective + " req/min (limit " + rpm + ")");
            return new ModelRateLimiter(effective);
        });
        limiter.acquireSlot();
    }

    private void acquireSlot() throws InterruptedException {
        // Claim the next slot: everyone who arrives gets a distinct, evenly-spaced timestamp.
        long now      = System.nanoTime();
        long mySlot   = nextSlotNanos.getAndUpdate(prev -> Math.max(prev, now) + intervalNanos);
        long fireAt   = Math.max(mySlot, now);
        long waitNanos = fireAt - now;
        if (waitNanos > 0) {
            Thread.sleep(waitNanos / 1_000_000L, (int) (waitNanos % 1_000_000L));
        }
    }
}
