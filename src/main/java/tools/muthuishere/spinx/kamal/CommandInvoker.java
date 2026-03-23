package tools.muthuishere.spinx.kamal;

/**
 * Strategy for executing a shell command string.
 *
 * <p>The default production implementation delegates to
 * {@link tools.muthuishere.spinx.Runner#runCommand(String)}.
 * Tests can supply an alternative implementation (e.g. a lambda that
 * records invocations) to verify the exact commands that would be
 * sent without actually spawning any OS processes.
 */
@FunctionalInterface
public interface CommandInvoker {
    void invoke(String command);
}
