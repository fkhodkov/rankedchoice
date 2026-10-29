package info.fkhodkov.rankedchoice.core.util;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Where a long count gives up once the thread that asked for it is interrupted, as a server's
 * {@code Future.cancel(true)} does when it stops waiting: by a {@link CancellationException},
 * the thread's interrupt left set. Counts check between rounds and inside their longest loops,
 * so one stops within a fraction of a second rather than running on to its end.
 *
 * <p>Work handed to other threads (parallel streams run on the common pool, whose threads
 * nobody interrupts) goes through a {@link Watch}: see there.
 */
public final class Interruption {

    private Interruption() {
    }

    /** Gives up if this thread was interrupted. */
    public static void check() {
        if (Thread.currentThread().isInterrupted()) {
            throw stopped();
        }
    }

    /** A watch over parallel work this thread hands out. */
    public static Watch watch() {
        return new Watch(Thread.currentThread());
    }

    /**
     * Parallel work handed out by one thread: a section ({@link #call} or {@link #run}) whose
     * pieces each run under {@link #enter}. A piece gives up once the thread that handed it out
     * is interrupted, or once the section has ended by an exception; and the section passes its
     * exception on only after every piece under way has finished, so nothing of the count is
     * still running when its caller hears it stopped.
     *
     * <p>The interrupt alone isn't enough: a stream gives up when its first piece does, while the
     * others are still running or queued, and a thread pool clears the interrupt as soon as its
     * thread goes back for more work.
     */
    public static final class Watch {

        private final Thread owner;
        private final AtomicInteger working = new AtomicInteger();
        private final Piece piece = working::decrementAndGet;
        private volatile boolean stopped;

        private Watch(Thread owner) {
            this.owner = owner;
        }

        /** Runs a parallel section and returns its result; see the class javadoc. */
        public <T> T call(Supplier<T> section) {
            try {
                return section.get();
            } catch (RuntimeException | Error e) {
                stopped = true;
                while (working.get() > 0) {
                    Thread.onSpinWait();
                }
                throw e;
            }
        }

        /** Runs a parallel section; see the class javadoc. */
        public void run(Runnable section) {
            call(() -> {
                section.run();
                return null;
            });
        }

        /** Starts a piece of the section's work, or gives up; close it when the piece is done. */
        public Piece enter() {
            working.incrementAndGet();
            if (stopped || owner.isInterrupted()) {
                stopped = true;
                working.decrementAndGet();
                throw stopped();
            }
            return piece;
        }

        /** Gives up partway through a piece, on the same terms as {@link #enter}. */
        public void check() {
            if (stopped || owner.isInterrupted()) {
                stopped = true;
                throw stopped();
            }
        }
    }

    /** A piece of parallel work under way; closing it says it's done. */
    @FunctionalInterface
    public interface Piece extends AutoCloseable {
        @Override
        void close();
    }

    private static CancellationException stopped() {
        return new CancellationException("the count was stopped");
    }
}
