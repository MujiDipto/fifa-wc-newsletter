package com.muji.worldcup.concurrency;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class PrioritisedJobSchedulerTest {

    private PrioritisedJobScheduler scheduler;

    @BeforeEach
    void setUp() {
        // Single worker so priority ordering is deterministic
        scheduler = new PrioritisedJobScheduler(1);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        scheduler.shutdown();
        scheduler.awaitTermination(2, TimeUnit.SECONDS);
    }

    @Test
    void submittedTaskReturnsResult() throws Exception {
        Future<String> f = scheduler.submit(() -> "hello", PrioritisedJobScheduler.Priority.HIGH);
        assertEquals("hello", f.get(2, TimeUnit.SECONDS));
    }

    @Test
    void highPriorityRunsBeforeNormal() throws Exception {
        // Saturate the single worker with a blocking task so the queue fills up,
        // then submit NORMAL followed by HIGH and verify HIGH completes first.
        CountDownLatch blockWorker = new CountDownLatch(1);
        CountDownLatch workerBlocked = new CountDownLatch(1);

        scheduler.submit(() -> {
            workerBlocked.countDown();
            blockWorker.await();
            return null;
        }, PrioritisedJobScheduler.Priority.NORMAL);

        // Wait until the worker is busy
        workerBlocked.await(2, TimeUnit.SECONDS);

        List<String> completionOrder = new ArrayList<>();
        Future<?> normal = scheduler.submit(() -> { completionOrder.add("NORMAL"); return null; },
                PrioritisedJobScheduler.Priority.NORMAL);
        Future<?> high   = scheduler.submit(() -> { completionOrder.add("HIGH");   return null; },
                PrioritisedJobScheduler.Priority.HIGH);

        // Release the worker
        blockWorker.countDown();
        high.get(3, TimeUnit.SECONDS);
        normal.get(3, TimeUnit.SECONDS);

        assertEquals(List.of("HIGH", "NORMAL"), completionOrder);
    }

    @Test
    void exceptionSurfacesThroughFuture() {
        Future<?> f = scheduler.submit(() -> { throw new IllegalStateException("fail"); },
                PrioritisedJobScheduler.Priority.NORMAL);
        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> f.get(2, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, ex.getCause());
    }

    @Test
    void shutdownRejectsNewSubmissions() throws InterruptedException {
        scheduler.shutdown();
        scheduler.awaitTermination(1, TimeUnit.SECONDS);
        assertThrows(RejectedExecutionException.class,
                () -> scheduler.submit(() -> 1, PrioritisedJobScheduler.Priority.HIGH));
    }
}
