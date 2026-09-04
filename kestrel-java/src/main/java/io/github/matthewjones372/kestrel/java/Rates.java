package io.github.matthewjones372.kestrel.java;

import io.github.matthewjones372.kestrel.Rate;

/**
 * How often virtual users arrive. Kotlin writes {@code 50.perSecond}, which is
 * an extension on a value class and so has a hash in the name it compiles to.
 */
public final class Rates {

    private Rates() {
    }

    public static Rate perSecond(double rate) {
        return (Rate) Boxes.perSecond(rate);
    }

    public static Rate perMinute(double rate) {
        return (Rate) Boxes.perMinute(rate);
    }
}
