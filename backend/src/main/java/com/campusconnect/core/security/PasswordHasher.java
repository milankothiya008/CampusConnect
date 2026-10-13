package com.campusconnect.core.security;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * BCrypt (cost 12, ~250 ms of CPU) on a small dedicated pool of platform threads (utils/Password.js used worker
 * threads for the same reason). The calling virtual thread just waits, so a burst of sign-ins can never occupy
 * every CPU and stall the rest of the API. Sign-in throughput per replica is therefore about 4 per second per
 * hashing thread. The JVM counts a container's CPU limit, so a 1.5-CPU replica gets one thread; give replicas more
 * CPUs to take more sign-ins ({@code app.security.password-hash-threads} overrides the count, but hashing on every
 * CPU slows every other request down).
 */
@Component
public class PasswordHasher {

    private final PasswordEncoder encoder;
    private final ExecutorService pool;

    public PasswordHasher(PasswordEncoder encoder, @Value("${app.security.password-hash-threads:0}") int threads) {
        this.encoder = encoder;
        int size = threads > 0 ? threads : Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1));
        AtomicInteger counter = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(size, runnable -> {
            Thread thread = new Thread(runnable, "password-hash-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    public String hash(String password) {
        return run(pool.submit(() -> encoder.encode(password)));
    }

    public boolean matches(String password, String hash) {
        if (password == null || hash == null || hash.isEmpty()) {
            return false;
        }
        return run(pool.submit(() -> encoder.matches(password, hash)));
    }

    private static <T> T run(Future<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while hashing a password", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Password hashing failed", e.getCause());
        }
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }
}
