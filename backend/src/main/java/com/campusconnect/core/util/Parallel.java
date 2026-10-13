package com.campusconnect.core.util;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

/**
 * Independent reads at once, one virtual thread each (the {@code Promise.all} of the Node services): a request that
 * needs several unrelated queries waits for the slowest instead of their sum. Closing waits for every task; a task's
 * own exception comes back unchanged from {@code get()}, so API errors keep their status.
 *
 * <pre>{@code
 * try (Parallel parallel = new Parallel()) {
 *     Supplier<Long> unread = parallel.fork(() -> inbox.unreadCount(actor));
 *     Supplier<List<Map<String, Object>>> events = parallel.fork(() -> campusEvents(actor));
 *     return view(unread.get(), events.get());
 * }
 * }</pre>
 */
public final class Parallel implements AutoCloseable {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public <T> Supplier<T> fork(Callable<T> task) {
        Future<T> future = executor.submit(task);
        return () -> join(future);
    }

    @Override
    public void close() {
        executor.close();
    }

    private static <T> T join(Future<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a parallel read", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(e.getCause());
        }
    }
}
