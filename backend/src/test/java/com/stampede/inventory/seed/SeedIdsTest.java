package com.stampede.inventory.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class SeedIdsTest {

    @Test
    void idsAreTheSameOnEveryMachine() {
        // Expected values computed independently (Python: MD5 of the name, version 3 and IETF variant bits),
        // so this would catch any change to the naming scheme, which would break existing databases.
        assertThat(SeedIds.venue("demo-arena"))
                .isEqualTo(UUID.fromString("51fe1952-9c25-328e-b99c-8c46790a02af"));
        assertThat(SeedIds.seat("demo-arena", "FL-L", "A", 1))
                .isEqualTo(UUID.fromString("70b0ad54-a4d5-35e1-bee9-bf32ba9b2de6"));
        assertThat(SeedIds.venue("demo-arena").version()).isEqualTo(3);
    }

    @Test
    void differentNamesGiveDifferentIds() {
        assertThat(SeedIds.seat("demo-arena", "101", "A", 1))
                .isNotEqualTo(SeedIds.seat("demo-arena", "101", "A", 2))
                .isNotEqualTo(SeedIds.seat("demo-arena", "102", "A", 1));
        assertThat(SeedIds.venue("demo-arena")).isNotEqualTo(SeedIds.event("demo-arena"));
    }
}
