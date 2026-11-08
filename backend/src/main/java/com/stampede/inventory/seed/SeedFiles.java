package com.stampede.inventory.seed;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.Resource;

/** Reads seed files and builds the venues.layout_json document. */
public final class SeedFiles {

    // Jackson's defaults reject unknown properties, so a typo in a field name fails loudly.
    private static final ObjectMapper JSON = new ObjectMapper();

    private SeedFiles() {}

    public static VenueFile readVenue(Resource resource) {
        return read(resource, VenueFile.class);
    }

    public static EventSeedFile readEvent(Resource resource) {
        return read(resource, EventSeedFile.class);
    }

    private static <T> T read(Resource resource, Class<T> type) {
        try (InputStream in = resource.getInputStream()) {
            return JSON.readValue(in, type);
        } catch (IOException e) {
            throw new SeedException("Cannot read seed file " + resource.getDescription() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Everything in the venue file except the rows: what a renderer needs to draw the venue zoomed out.
     * Seats are stored only in the seats table, so there is a single source of truth for them.
     */
    public static String layoutJson(VenueFile venue) {
        Map<String, Object> layout = new LinkedHashMap<>();
        layout.put("formatVersion", venue.formatVersion());
        layout.put("slug", venue.slug());
        layout.put("name", venue.name());
        layout.put("width", venue.width());
        layout.put("height", venue.height());
        layout.put("seatRadius", venue.seatRadius());
        layout.put("stage", venue.stage());
        layout.put("sections", venue.sections().stream().map(SeedFiles::sectionLayout).toList());
        try {
            return JSON.writeValueAsString(layout);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise venue layout", e);
        }
    }

    private static Map<String, Object> sectionLayout(VenueFile.Section section) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("code", section.code());
        s.put("name", section.name());
        s.put("label", section.label());
        s.put("outline", section.outline() == null ? List.of() : section.outline());
        return s;
    }
}
