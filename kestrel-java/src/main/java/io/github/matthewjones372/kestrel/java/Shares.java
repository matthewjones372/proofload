package io.github.matthewjones372.kestrel.java;

import io.github.matthewjones372.kestrel.Share;

/** A percentage, which is how the goals about failures and goodput are stated. */
public final class Shares {

    private Shares() {
    }

    public static Share percent(double percent) {
        return (Share) Boxes.percent(percent);
    }
}
