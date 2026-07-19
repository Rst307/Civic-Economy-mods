package org.civiceconomy.platform.neoforge;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.civiceconomy.nation.OnlineTimeLedger;
import org.civiceconomy.nation.RecordOnlineTime;
import org.civiceconomy.persistence.CivicDatabase;

final class AsyncOnlineTimeWriter implements AutoCloseable {
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);

    private final CivicDatabase database;
    private final OnlineTimeLedger ledger;
    private final ExecutorService executor;
    private final AtomicReference<Throwable> firstFailure = new AtomicReference<>();

    AsyncOnlineTimeWriter(CivicDatabase database) {
        this.database = database;
        this.ledger = new OnlineTimeLedger(database);
        this.executor = Executors.newSingleThreadExecutor(
                Thread.ofPlatform().name("Civic-Economy-SQLite").daemon(false).factory());
    }

    void submit(List<RecordOnlineTime> completed) {
        if (completed.isEmpty() || firstFailure.get() != null) {
            return;
        }
        List<RecordOnlineTime> immutable = List.copyOf(completed);
        executor.execute(() -> {
            try {
                for (RecordOnlineTime interval : immutable) {
                    ledger.record(interval);
                }
            } catch (RuntimeException failure) {
                firstFailure.compareAndSet(null, failure);
            }
        });
    }

    Throwable failure() {
        return firstFailure.get();
    }

    <T> CompletableFuture<T> submitDatabase(Function<CivicDatabase, T> work) {
        Throwable unavailable = firstFailure.get();
        if (unavailable != null) {
            return CompletableFuture.failedFuture(unavailable);
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    result.complete(work.apply(database));
                } catch (RuntimeException failure) {
                    result.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException rejected) {
            result.completeExceptionally(rejected);
        }
        return result;
    }

    @Override
    public void close() {
        executor.execute(() -> {
            try {
                database.close();
            } catch (RuntimeException failure) {
                firstFailure.compareAndSet(null, failure);
            }
        });
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
                throw new IllegalStateException("Timed out draining Civic SQLite writes during server shutdown");
            }
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while draining Civic SQLite writes", interrupted);
        }
        Throwable failure = firstFailure.get();
        if (failure != null) {
            throw new IllegalStateException("Civic online-time persistence failed closed", failure);
        }
    }
}
