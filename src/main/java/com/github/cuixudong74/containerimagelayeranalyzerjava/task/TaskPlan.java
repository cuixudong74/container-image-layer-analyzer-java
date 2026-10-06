package com.github.cuixudong74.containerimagelayeranalyzerjava.task;

/**
 * A task decomposed into sequential work units (typically one per layer).
 * Units of different tasks interleave on the shared executor, so a single
 * huge task cannot starve the others.
 */
public interface TaskPlan extends AutoCloseable {

    /** Total number of work units (for progress reporting); -1 if unknown. */
    int totalUnits();

    /** Executes one work unit; returns true when more units remain. */
    boolean step() throws Exception;

    /** The task result; valid only after the last unit completed. */
    Object result();

    /** Releases all resources and removes intermediate artifacts. */
    @Override
    void close() throws Exception;
}
