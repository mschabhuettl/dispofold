package app.dispofold.api;

import java.util.Set;

import io.quarkus.test.junit.QuarkusTestProfile;

/** Replaces the real solver with {@link FakePlanningFacade}. */
public class FakeFacadeProfile implements QuarkusTestProfile {

    @Override
    public Set<Class<?>> getEnabledAlternatives() {
        return Set.of(FakePlanningFacade.class);
    }
}
