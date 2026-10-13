package com.campusconnect.core.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ParallelTest {

    @Test
    void runsTheTasksAtOnceAndReturnsEachResult() {
        CountDownLatch bothRunning = new CountDownLatch(2);
        try (Parallel parallel = new Parallel()) {
            Supplier<String> first = parallel.fork(() -> {
                bothRunning.countDown();
                assertThat(bothRunning.await(5, TimeUnit.SECONDS)).as("the other task runs at the same time").isTrue();
                return "first";
            });
            Supplier<String> second = parallel.fork(() -> {
                bothRunning.countDown();
                assertThat(bothRunning.await(5, TimeUnit.SECONDS)).as("the other task runs at the same time").isTrue();
                return "second";
            });
            assertThat(first.get()).isEqualTo("first");
            assertThat(second.get()).isEqualTo("second");
        }
    }

    @Test
    void rethrowsATasksOwnRuntimeExceptionUnchanged() {
        IllegalArgumentException failure = new IllegalArgumentException("not found");
        try (Parallel parallel = new Parallel()) {
            Supplier<Object> failing = parallel.fork(() -> {
                throw failure;
            });
            assertThatThrownBy(failing::get).isSameAs(failure);
        }
    }

    @Test
    void wrapsACheckedException() {
        try (Parallel parallel = new Parallel()) {
            Supplier<Object> failing = parallel.fork(() -> {
                throw new IOException("disk");
            });
            assertThatThrownBy(failing::get).isInstanceOf(IllegalStateException.class).hasCauseInstanceOf(IOException.class);
        }
    }
}
