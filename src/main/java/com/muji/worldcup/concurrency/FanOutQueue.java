package com.muji.worldcup.concurrency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Fans a list of inputs out across a {@link BoundedWorkerPool} concurrently using
 * {@link CompletableFuture}. Successful results are collected; failed inputs are
 * recorded in a dead-letter list rather than propagating exceptions to the caller.
 */
public class FanOutQueue<T, R> {

    private static final Logger log = LoggerFactory.getLogger(FanOutQueue.class);

    private final BoundedWorkerPool pool;
    private final List<T> deadLetters = Collections.synchronizedList(new ArrayList<>());

    public FanOutQueue(BoundedWorkerPool pool) {
        this.pool = pool;
    }

    public List<R> process(List<T> inputs, Function<T, R> processor) {
        List<CompletableFuture<R>> futures = inputs.stream()
                .map(input -> CompletableFuture
                        .supplyAsync(() -> processor.apply(input), pool.executor())
                        .exceptionally(ex -> {
                            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                            log.warn("FanOutQueue: processing failed for {}: {}", input, cause.getMessage(), cause);
                            deadLetters.add(input);
                            return null;
                        }))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        List<R> results = futures.stream()
                .map(f -> f.getNow(null))
                .filter(Objects::nonNull)
                .toList();

        if (!deadLetters.isEmpty()) {
            log.error("FanOutQueue: {} item(s) in dead-letter list: {}", deadLetters.size(), deadLetters);
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
