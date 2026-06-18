package com.muji.worldcup.concurrency;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class FanOutQueueTest {

    private BoundedWorkerPool pool;
    private FanOutQueue<Integer, Integer> fanOut;

    @BeforeEach
    void setUp() {
        pool = new BoundedWorkerPool(4, 32);
        fanOut = new FanOutQueue<>(pool);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        pool.shutdown();
        pool.awaitTermination(2, TimeUnit.SECONDS);
    }

    @Test
    void processesAllInputs() {
        List<Integer> results = fanOut.process(List.of(1, 2, 3, 4), x -> x * 10);
        assertEquals(4, results.size());
        assertTrue(results.containsAll(List.of(10, 20, 30, 40)));
    }

    @Test
    void failedInputsGoToDeadLetters() {
        List<Integer> results = fanOut.process(List.of(1, 2, 3), x -> {
            if (x == 2) throw new RuntimeException("bad input");
            return x;
        });
        assertEquals(2, results.size());
        assertEquals(1, fanOut.deadLetters().size());
        assertEquals(2, fanOut.deadLetters().get(0));
    }

    @Test
    void deadLettersCanBeCleared() {
        fanOut.process(List.of(1), x -> { throw new RuntimeException(); });
        assertFalse(fanOut.deadLetters().isEmpty());
        fanOut.clearDeadLetters();
        assertTrue(fanOut.deadLetters().isEmpty());
    }

    @Test
    void emptyInputReturnsEmptyResults() {
        List<Integer> results = fanOut.process(List.of(), x -> x);
        assertTrue(results.isEmpty());
        assertTrue(fanOut.deadLetters().isEmpty());
    }

    @Test
    void allFailuresLandInDeadLetters() {
        List<Integer> results = fanOut.process(List.of(1, 2, 3),
                x -> { throw new RuntimeException("always fail"); });
        assertTrue(results.isEmpty());
        assertEquals(3, fanOut.deadLetters().size());
    }
}
