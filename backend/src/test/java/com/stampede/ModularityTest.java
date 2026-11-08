package com.stampede;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.core.NamedInterface;

/**
 * Enforces the module boundaries from D-007 (docs/DECISIONS.md). Plain unit test: it analyses the
 * compiled production classes with ArchUnit, so it needs no Spring context and no Docker.
 */
class ModularityTest {

    private static final ApplicationModules MODULES = ApplicationModules.of(StampedeApplication.class);

    /**
     * Fails on: references to another module's internal packages, cycles, and any dependency missing
     * from a module's allowedDependencies (including using an `api` type where only `events` is allowed).
     */
    @Test
    void moduleBoundariesAreRespected() {
        MODULES.verify();
    }

    /**
     * Guards the test above from passing vacuously: if a module or named interface weren't detected
     * (for example a package that only holds package-info), verify() would have nothing to check.
     */
    @Test
    void allModulesAndNamedInterfacesAreDetected() {
        Map<String, Set<String>> namedInterfaces = MODULES.stream()
                .collect(Collectors.toMap(
                        module -> module.getIdentifier().toString(),
                        ModularityTest::namedInterfaceNames));

        assertThat(namedInterfaces).containsOnlyKeys(
                "identity", "waitingroom", "inventory", "booking",
                "payments", "ticketing", "realtime", "control", "shared");

        assertThat(namedInterfaces).containsEntry("identity", Set.of("api"));
        assertThat(namedInterfaces).containsEntry("waitingroom", Set.of("api", "events"));
        assertThat(namedInterfaces).containsEntry("inventory", Set.of("api", "events"));
        assertThat(namedInterfaces).containsEntry("booking", Set.of("api", "events"));
        assertThat(namedInterfaces).containsEntry("payments", Set.of("api", "events"));
        assertThat(namedInterfaces).containsEntry("ticketing", Set.of("api", "events"));
        assertThat(namedInterfaces).containsEntry("realtime", Set.of());
        assertThat(namedInterfaces).containsEntry("control", Set.of());
        assertThat(namedInterfaces).containsEntry("shared", Set.of("security", "error"));
    }

    private static Set<String> namedInterfaceNames(ApplicationModule module) {
        return StreamSupport.stream(module.getNamedInterfaces().spliterator(), false)
                .filter(NamedInterface::isNamed)
                .map(NamedInterface::getName)
                .collect(Collectors.toSet());
    }
}
