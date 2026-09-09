package io.github.matthewjones372.proofload.java;

import io.github.matthewjones372.proofload.StepName;

/**
 * A step's name, declared once and shared by the scenario that defines the step
 * and every assertion about it.
 */
public final class Steps {

    private Steps() {
    }

    public static StepName named(String name) {
        return (StepName) Boxes.step(name);
    }
}
