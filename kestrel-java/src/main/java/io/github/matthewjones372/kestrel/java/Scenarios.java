package io.github.matthewjones372.kestrel.java;

import io.github.matthewjones372.kestrel.Action;
import io.github.matthewjones372.kestrel.Scenario;
import io.github.matthewjones372.kestrel.Step;
import io.github.matthewjones372.kestrel.StepName;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What a virtual user does, in order. Kotlin declares this with a lambda whose
 * receiver is a builder; Java has no such thing, so the builder is the value a
 * caller holds until {@link Builder#build()} freezes it into core's
 * {@code Scenario}.
 */
public final class Scenarios {

    private Scenarios() {
    }

    public static Builder named(String name) {
        return new Builder(name);
    }

    /** Mutable until {@link #build()}, which is the one place the steps are frozen. */
    public static final class Builder {

        private final String name;

        private final List<Step> steps = new ArrayList<>();

        private Builder(String name) {
            this.name = name;
        }

        public Builder exec(StepName step, Action action) {
            return exec(step.getName(), action);
        }

        public Builder exec(String step, Action action) {
            steps.add(new Step.Exec(step, action));
            return this;
        }

        /** A wait between steps, which records nothing and so has no name. */
        public Builder pause(Duration duration) {
            steps.add(Pauses.of(duration));
            return this;
        }

        public Scenario build() {
            return new Scenario(name, Collections.unmodifiableList(new ArrayList<>(steps)));
        }
    }
}
