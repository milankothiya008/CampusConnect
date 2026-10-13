package com.campusconnect.feature.chat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/**
 * Fire-and-forget work the original service started without awaiting it (link previews, "delivered" receipts when
 * the chat list opens, removing files of unsent messages). Runs on virtual threads; a failure is logged and never
 * reaches the request that started it.
 */
@Slf4j
@Component
public class ChatBackground implements DisposableBean {

    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("chat-background-", 0).factory());

    public void run(String what, Runnable task) {
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    log.warn("{} failed: {}", what, e.getMessage());
                }
            });
        } catch (RejectedExecutionException e) {
            log.debug("{} skipped while shutting down", what);
        }
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
