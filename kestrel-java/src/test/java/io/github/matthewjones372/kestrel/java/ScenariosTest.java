package io.github.matthewjones372.kestrel.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.matthewjones372.kestrel.Action;
import io.github.matthewjones372.kestrel.Scenario;
import io.github.matthewjones372.kestrel.SessionKey;
import io.github.matthewjones372.kestrel.Step;
import io.github.matthewjones372.kestrel.StepName;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ScenariosTest {

    private static final StepName BROWSE = Steps.named("browse");

    private static final SessionKey<String> SEEN = SessionKeys.of(String.class, "seen");

    private static final Action NOTE = Actions.of(scope -> scope.set(SEEN, "yes"));

    @Test
    void aStepIsNamedByTheHandleThatDeclaredIt() {
        Scenario built = Scenarios.named("checkout").exec(BROWSE, NOTE).build();

        assertEquals("checkout", built.getName());
        assertEquals(new Step.Exec("browse", NOTE), built.getSteps().get(0));
    }

    @Test
    void aPauseIsGivenAsAJavaDuration() {
        Scenario built = Scenarios.named("checkout").exec(BROWSE, NOTE).pause(Duration.ofSeconds(1)).build();

        assertEquals(2, built.getSteps().size());
        assertInstanceOf(Step.Pause.class, built.getSteps().get(1));
    }

    @Test
    void aScenarioIsFrozenOnceItIsBuilt() {
        Scenario built = Scenarios.named("checkout").exec(BROWSE, NOTE).build();

        assertThrows(UnsupportedOperationException.class, () -> built.getSteps().clear());
    }
}
