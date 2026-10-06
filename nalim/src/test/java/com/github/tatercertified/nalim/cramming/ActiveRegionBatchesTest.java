package com.github.tatercertified.nalim.cramming;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Deterministic boundary and publication checks; no external test framework. */
public final class ActiveRegionBatchesTest {
    private ActiveRegionBatchesTest() {}

    public static void main(String[] args) throws Exception {
        boundaryRouting();
        pageAllocationRace();
        publicationStress();
    }

    private static void boundaryRouting() {
        ActiveRegionBatches<Object> table = new ActiveRegionBatches<>();
        long[] paged = {1, 2, 1023, 1024, 1025, ActiveRegionBatches.MAX_PAGED_ID - 1};
        long[] fallback = {0, -1, ActiveRegionBatches.MAX_PAGED_ID,
                ActiveRegionBatches.MAX_PAGED_ID + 1, Long.MAX_VALUE};

        for (long id : paged) {
            check(ActiveRegionBatches.isPaged(id), "paged route " + id);
            roundTrip(table, id);
        }
        for (long id : fallback) {
            check(!ActiveRegionBatches.isPaged(id), "fallback route " + id);
            roundTrip(table, id);
        }

        Object lastPagedKey = new Object();
        Object firstFallbackKey = new Object();
        Object lastPagedValue = new Object();
        Object firstFallbackValue = new Object();
        table.publish(ActiveRegionBatches.MAX_PAGED_ID - 1, lastPagedKey, lastPagedValue);
        table.publish(ActiveRegionBatches.MAX_PAGED_ID, firstFallbackKey, firstFallbackValue);
        check(table.get(ActiveRegionBatches.MAX_PAGED_ID - 1, lastPagedKey) == lastPagedValue,
                "last paged value");
        check(table.get(ActiveRegionBatches.MAX_PAGED_ID, firstFallbackKey) == firstFallbackValue,
                "first fallback value");
        check(table.remove(ActiveRegionBatches.MAX_PAGED_ID - 1, lastPagedKey) == lastPagedValue,
                "last paged remove");
        check(table.remove(ActiveRegionBatches.MAX_PAGED_ID, firstFallbackKey) == firstFallbackValue,
                "first fallback remove");
    }

    private static void roundTrip(ActiveRegionBatches<Object> table, long id) {
        Object key = new Object();
        Object value = new Object();
        table.publish(id, key, value);
        check(table.get(id, key) == value, "lookup " + id);
        check(table.remove(id, key) == value, "remove " + id);
        check(table.get(id, key) == null, "cleared " + id);
    }

    private static void pageAllocationRace() throws Exception {
        int threads = 32;
        ActiveRegionBatches<Object> table = new ActiveRegionBatches<>();
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        Thread[] workers = new Thread[threads];
        Object[] values = new Object[threads];
        for (int i = 0; i < threads; i++) {
            int slot = i;
            values[i] = new Object();
            workers[i] = new Thread(() -> {
                ready.countDown();
                await(start);
                table.publish(2048L + slot, values[slot], values[slot]);
            }, "active-table-page-race-" + i);
            workers[i].start();
        }
        boolean allReady = ready.await(30, TimeUnit.SECONDS);
        start.countDown();
        check(allReady, "page-race workers ready");
        for (Thread worker : workers) join(worker);
        check(table.allocatedPageCount() == 1, "one page allocated");
        for (int i = 0; i < threads; i++) {
            check(table.get(2048L + i, values[i]) == values[i], "raced slot " + i);
        }
    }

    private static void publicationStress() throws Exception {
        ActiveRegionBatches<Payload> table = new ActiveRegionBatches<>();
        Object key = new Object();
        AtomicBoolean done = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread[] readers = new Thread[4];
        for (int i = 0; i < readers.length; i++) {
            readers[i] = new Thread(() -> {
                while (!done.get()) {
                    Payload payload = table.get(17, key);
                    if (payload != null && (payload.guard != ~((long) payload.generation)
                            || payload.marker != payload)) {
                        failure.compareAndSet(null, new AssertionError("partially published payload"));
                        return;
                    }
                }
            }, "active-table-reader-" + i);
            readers[i].start();
        }
        for (int generation = 0; generation < 100_000; generation++) {
            Payload payload = new Payload();
            payload.generation = generation;
            payload.guard = ~((long) generation);
            payload.marker = payload;
            table.publish(17, key, payload);
            table.remove(17, key);
        }
        done.set(true);
        for (Thread reader : readers) join(reader);
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static void join(Thread thread) {
        try {
            thread.join(30_000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
        if (thread.isAlive()) {
            thread.interrupt();
            throw new AssertionError("timed out waiting for " + thread.getName());
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Payload {
        int generation;
        long guard;
        Object marker;
    }
}
