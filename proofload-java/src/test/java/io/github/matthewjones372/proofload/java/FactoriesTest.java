package io.github.matthewjones372.proofload.java;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.matthewjones372.proofload.Rate;
import io.github.matthewjones372.proofload.SessionKey;
import io.github.matthewjones372.proofload.StepName;
import org.junit.jupiter.api.Test;

/**
 * Java source rather than Kotlin, because the claim is that a Java compiler can
 * resolve these calls. Kotlin resolves the mangled names too, so a Kotlin test
 * here would pass while the module failed at the only thing it is for.
 */
class FactoriesTest {

    @Test
    void aRateIsBuiltFromAPerSecondFigure() {
        Rate rate = Rates.perSecond(50);

        assertEquals(50.0, rate.getPerSecond());
        assertEquals(0.5, Rates.perMinute(30).getPerSecond());
    }

    @Test
    void aStepNameIsBuiltFromAString() {
        StepName step = Steps.named("pay");

        assertEquals("pay", step.getName());
    }

    @Test
    void aShareIsBuiltFromAPercentage() {
        assertEquals(2.5, Shares.percent(2.5).getPercent());
    }

    @Test
    void aSessionKeyCarriesItsTypeWithoutAReifiedParameter() {
        SessionKey<String> orderId = SessionKeys.of(String.class, "orderId");

        assertEquals("SessionKey(orderId: String)", orderId.toString());
        assertEquals(SessionKeys.of(String.class, "orderId"), orderId);
    }
}
