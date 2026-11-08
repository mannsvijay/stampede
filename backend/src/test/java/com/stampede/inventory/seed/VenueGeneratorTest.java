package com.stampede.inventory.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class VenueGeneratorTest {

    @Test
    void generatorOutputMatchesCommittedFile() throws IOException {
        // Gradle runs tests from the backend/ directory. Line endings are normalised because Git on
        // Windows may check the file out with CRLF; the generator always writes LF.
        String committed = Files.readString(Path.of(VenueGenerator.OUTPUT), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        String generated = VenueGenerator.toJson(VenueGenerator.generate());

        assertThat(generated.equals(committed))
                .as("%s is out of date with VenueGenerator. Run: cd backend && ./gradlew generateVenue",
                        VenueGenerator.OUTPUT)
                .isTrue();
    }

    @Test
    void generatorIsDeterministic() {
        assertThat(VenueGenerator.toJson(VenueGenerator.generate()))
                .isEqualTo(VenueGenerator.toJson(VenueGenerator.generate()));
    }
}
