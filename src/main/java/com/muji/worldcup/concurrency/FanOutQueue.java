package com.muji.worldcup.concurrency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Hand-rolled fan-out queue. Distributes a list of inputs across the worker pool
 * concurrently, collects successful results, and records failed inputs in a
 * dead-letter list rather than propagating exceptions to the caller.
 *
 * Used in Phase 4 for concurrent email delivery; built here for Phase 2 to fan
 * out ContextBundle assembly across all subscribers.
 */
public class FanOutQueue<T, R> {

    private static final Logger log = LoggerFactory.getLogger(FanOutQueue.class);

    private final BoundedWorkerPool pool;
    private final List<T> deadLetters = Collections.synchronizedList(new ArrayList<>());

    public FanOutQueue(BoundedWorkerPool pool) {
        this.pool = pool;
    }

    /**
     * Fans out {@code processor} over all {@code inputs} concurrently.
     * Returns the list of successful results. Failed inputs are recorded in
     * {@link #deadLetters()} and logged as warnings.
     */
    public List<R> process(List<T> inputs, Function<T, R> processor) {
        List<Future<R>> futures = new ArrayList<>(inputs.size());

        for (T input : inputs) {
            futures.add(pool.submit(() -> {
                try {
                    return processor.apply(input);
                } catch (Exception e) {
                    log.warn("FanOutQueue: processing failed for {}: {}", input, e.getMessage());
                    deadLetters.add(input);
                    return null;
                }
            }));
        }

        List<R> results = new ArrayList<>(inputs.size());
        for (int i = 0; i < futures.size(); i++) {
            try {
                R result = futures.get(i).get(30, TimeUnit.SECONDS);
                if (result != null) results.add(result);
            } catch (Exception e) {
                T input = inputs.get(i);
                log.warn("FanOutQueue: future failed for {}: {}", input, e.getMessage());
                deadLetters.add(input);
            }
        }

        if (!deadLetters.isEmpty()) {
            log.error("FanOutQueue: {} item(s) landed in dead-letter list: {}", deadLetters.size(), deadLetters);
        }

        return results;
    }

    public List<T> deadLetters() {
        return Collections.unmodifiableList(deadLetters);
    }

    public void clearDeadLetters() {
        deadLetters.clear();
    }
}
