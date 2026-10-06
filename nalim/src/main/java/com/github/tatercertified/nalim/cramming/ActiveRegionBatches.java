package com.github.tatercertified.nalim.cramming;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Sparse, exact-id publication table for batches owned by Fabrolia regions.
 *
 * <p>A slot write is the release edge for every plain field and native-buffer
 * write in a completed batch; readers acquire through the matching slot read.
 * Callers must not retain a returned batch past the region's post-tick fan-in.
 * Fast slots require Fabrolia's globally unique, never-reused {@code Region.id};
 * ids outside the bounded table keep exact map semantics.
 */
final class ActiveRegionBatches<T> {
    static final int PAGE_SHIFT = 10;
    static final int PAGE_SIZE = 1 << PAGE_SHIFT;
    static final long MAX_PAGED_ID = 1L << 22;
    private static final int PAGE_MASK = PAGE_SIZE - 1;
    private static final int PAGE_COUNT = (int) (MAX_PAGED_ID >>> PAGE_SHIFT);

    private final AtomicReferenceArray<AtomicReferenceArray<T>> pages =
            new AtomicReferenceArray<>(PAGE_COUNT);
    private final ConcurrentHashMap<Object, T> fallback = new ConcurrentHashMap<>();
    private final AtomicInteger allocatedPages = new AtomicInteger();

    T get(long id, Object key) {
        if (!isPaged(id)) {
            return fallback.get(key);
        }
        AtomicReferenceArray<T> page = pages.get((int) (id >>> PAGE_SHIFT));
        return page == null ? null : page.get((int) (id & PAGE_MASK));
    }

    void publish(long id, Object key, T value) {
        Objects.requireNonNull(value, "value");
        if (!isPaged(id)) {
            fallback.put(key, value);
            return;
        }

        int pageIndex = (int) (id >>> PAGE_SHIFT);
        AtomicReferenceArray<T> page = pages.get(pageIndex);
        if (page == null) {
            AtomicReferenceArray<T> candidate = new AtomicReferenceArray<>(PAGE_SIZE);
            if (pages.compareAndSet(pageIndex, null, candidate)) {
                page = candidate;
                allocatedPages.incrementAndGet();
            } else {
                page = pages.get(pageIndex);
            }
        }
        page.set((int) (id & PAGE_MASK), value);
    }

    T remove(long id, Object key) {
        if (!isPaged(id)) {
            return fallback.remove(key);
        }
        AtomicReferenceArray<T> page = pages.get((int) (id >>> PAGE_SHIFT));
        return page == null ? null : page.getAndSet((int) (id & PAGE_MASK), null);
    }

    int allocatedPageCount() {
        return allocatedPages.get();
    }

    static boolean isPaged(long id) {
        // Bounds-check the long before either narrowing conversion. In
        // particular, ids above 2^32 must never alias a low live region.
        return id > 0 && id < MAX_PAGED_ID;
    }
}
