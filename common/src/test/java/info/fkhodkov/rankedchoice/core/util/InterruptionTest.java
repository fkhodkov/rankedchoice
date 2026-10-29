package info.fkhodkov.rankedchoice.core.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class InterruptionTest {

    @AfterEach
    void clearTheInterrupt() {
        Thread.interrupted();
    }

    @Test
    void givesUpOnlyOnceTheThreadIsInterruptedAndLeavesTheInterruptSet() {
        assertThatCode(Interruption::check).doesNotThrowAnyException();

        Thread.currentThread().interrupt();

        assertThatThrownBy(Interruption::check).isInstanceOf(CancellationException.class)
                .hasMessage("the count was stopped");
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void workHandedToAnotherThreadChecksTheThreadThatHandedItOut() {
        Interruption.Watch watch = Interruption.watch();
        assertThat(enterElsewhere(watch)).isNull();

        Thread.currentThread().interrupt();

        assertThat(enterElsewhere(watch)).isInstanceOf(CancellationException.class);
    }

    @Test
    void workStaysStoppedOnceTheInterruptIsCleared() {
        // As a thread pool clears it when its thread goes back for the next job.
        Interruption.Watch watch = Interruption.watch();
        Thread.currentThread().interrupt();
        assertThat(enterElsewhere(watch)).isInstanceOf(CancellationException.class);

        Thread.interrupted();

        assertThat(enterElsewhere(watch)).isInstanceOf(CancellationException.class);
    }

    @Test
    void aSectionThatFailsStopsItsOtherPieces() {
        Interruption.Watch watch = Interruption.watch();

        assertThatThrownBy(() -> watch.run(() -> {
            throw new IllegalStateException("one piece failed");
        })).hasMessage("one piece failed");

        assertThat(enterElsewhere(watch)).isInstanceOf(CancellationException.class);
        assertThatThrownBy(watch::check).isInstanceOf(CancellationException.class);
    }

    @Test
    void aSectionThatFailsWaitsForThePiecesUnderWay() throws Exception {
        Interruption.Watch watch = Interruption.watch();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        CompletableFuture<Void> piece = CompletableFuture.runAsync(() -> {
            try (Interruption.Piece _ = watch.enter()) {
                entered.countDown();
                finish.await();
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        });
        entered.await();
        CompletableFuture<Void> section = CompletableFuture.runAsync(() -> watch.run(() -> {
            throw new CancellationException("the count was stopped");
        }));

        Thread.sleep(100);
        assertThat(section).isNotDone();

        finish.countDown();
        piece.get(5, TimeUnit.SECONDS);
        assertThatThrownBy(() -> section.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(CancellationException.class);
    }

    /** What {@code watch.enter()} throws on a thread of its own, which nobody interrupts. */
    private static Throwable enterElsewhere(Interruption.Watch watch) {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread other = Thread.ofPlatform().start(() -> {
            try (Interruption.Piece _ = watch.enter()) {
                // nothing to do
            } catch (CancellationException e) {
                thrown.set(e);
            }
        });
        while (other.isAlive()) {
            Thread.onSpinWait(); // join() would refuse to wait while this thread is interrupted
        }
        return thrown.get();
    }
}
