package info.fkhodkov.rankedchoice.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link Main#endOnAnUncaughtError}: in a program of its own, since it halts the one it's in, a
 * thread dies of an error while the main thread waits forever, as a count did for its out-of-memory
 * worker's task.
 */
class UncaughtErrorTest {

    private Process program;

    @AfterEach
    void stopTheProgram() {
        if (program != null) {
            program.destroyForcibly();
        }
    }

    @Test
    void aThreadOutOfMemoryEndsTheProgramWithTheMessage() throws Exception {
        program = run("memory");

        assertThat(program.waitFor(20, TimeUnit.SECONDS)).as("the program ended").isTrue();
        assertThat(program.exitValue()).isEqualTo(1);
        assertThat(new String(program.getErrorStream().readAllBytes(), StandardCharsets.UTF_8))
                .startsWith("Error: the count ran out of memory (Java's heap is ");
    }

    @Test
    void anyOtherErrorEndsItAsAFault() throws Exception {
        program = run("fault");

        assertThat(program.waitFor(20, TimeUnit.SECONDS)).as("the program ended").isTrue();
        assertThat(program.exitValue()).isEqualTo(1);
        assertThat(new String(program.getErrorStream().readAllBytes(), StandardCharsets.UTF_8))
                .startsWith("Error: an internal fault, please report it: java.lang.IllegalStateException: lost");
    }

    @Test
    void threadsDyingTogetherStillSayIt() throws Exception {
        program = run("together");

        assertThat(program.waitFor(20, TimeUnit.SECONDS)).as("the program ended").isTrue();
        assertThat(program.exitValue()).isEqualTo(1);
        assertThat(new String(program.getErrorStream().readAllBytes(), StandardCharsets.UTF_8))
                .startsWith("Error: an internal fault, please report it: java.lang.IllegalStateException: lost")
                .containsOnlyOnce("Error:");
    }

    private static Process run(String error) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), Stuck.class.getName(), error)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
    }

    /** Waits forever on a thread that dies, or on eight that die together. */
    static final class Stuck {
        public static void main(String[] args) throws InterruptedException {
            Main.endOnAnUncaughtError();
            int threads = args[0].equals("together") ? 8 : 1;
            CountDownLatch go = new CountDownLatch(1);
            for (int i = 0; i < threads; i++) {
                Thread.ofPlatform().start(() -> {
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    if (args[0].equals("memory")) {
                        throw new OutOfMemoryError("Java heap space");
                    }
                    throw new IllegalStateException("lost");
                });
            }
            go.countDown();
            Thread.currentThread().join();
        }
    }
}
