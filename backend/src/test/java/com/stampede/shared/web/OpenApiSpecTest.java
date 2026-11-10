package com.stampede.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stampede.shared.error.ErrorCode;
import com.stampede.testsupport.TestcontainersConfiguration;

/**
 * The committed OpenAPI spec (docs/openapi.json) must equal what the code generates (D-010). The
 * frontend's TypeScript client is generated from that file, so this test is what locks the contract.
 *
 * <p>When it fails because you changed the API on purpose, the fresh spec is in
 * build/openapi/openapi.json: review the difference and copy it over docs/openapi.json.
 *
 * <p>Needs the {@code openapi} profile (the endpoint is off by default), so it runs in its own
 * application context with its own containers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("openapi")
class OpenApiSpecTest {

    static final Path COMMITTED = Path.of("..", "docs", "openapi.json");
    static final Path GENERATED = Path.of("build", "openapi", "openapi.json");

    @Autowired
    TestRestTemplate http;

    @Autowired
    ObjectMapper json;

    private String generatedText;
    private JsonNode generated;

    @BeforeEach
    void fetchSpec() throws IOException {
        ResponseEntity<String> response = http.getForEntity("/v3/api-docs", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        generatedText = response.getBody();
        generated = json.readTree(generatedText);
        Files.createDirectories(GENERATED.getParent());
        Files.writeString(GENERATED, generatedText + "\n", StandardCharsets.UTF_8);
    }

    @Test
    void committedSpecMatchesTheCode() throws IOException {
        if (!Files.exists(COMMITTED)) {
            fail("docs/openapi.json is missing. Copy backend/" + GENERATED + " to docs/openapi.json.");
        }
        // Compared as JSON trees, so line endings and whitespace (e.g. git's CRLF on Windows) don't matter.
        JsonNode committed = json.readTree(Files.readString(COMMITTED, StandardCharsets.UTF_8));
        if (!committed.equals(generated)) {
            fail("docs/openapi.json is out of date with the code. If the API change is intended, review "
                    + "and copy backend/" + GENERATED + " over docs/openapi.json "
                    + "(PowerShell: Copy-Item backend\\build\\openapi\\openapi.json docs\\openapi.json).");
        }
    }

    @Test
    void specCoversTheAuthEventHoldOrderAndTicketEndpointsOnly() {
        List<String> paths = fieldNames(generated.path("paths"));

        assertThat(paths).containsExactlyInAnyOrder(
                "/auth/register", "/auth/login", "/auth/refresh", "/auth/logout",
                "/events", "/events/{id}",
                "/events/{id}/holds", "/holds/{id}",
                "/orders", "/orders/{id}",
                "/me/tickets", "/tickets/keys");
    }

    @Test
    void ticketEndpointsDocumentTheirResponses() {
        JsonNode wallet = generated.path("paths").path("/me/tickets").path("get");
        assertThat(wallet.path("operationId").asText()).isEqualTo("myTickets");
        assertThat(fieldNames(wallet.path("responses"))).containsExactlyInAnyOrder("200", "401", "500");

        // Public: scanners fetch the keys without logging in, so no 401 is documented.
        JsonNode keys = generated.path("paths").path("/tickets/keys").path("get");
        assertThat(keys.path("operationId").asText()).isEqualTo("ticketKeys");
        assertThat(fieldNames(keys.path("responses"))).containsExactlyInAnyOrder("200", "500");
    }

    @Test
    void orderEndpointsDocumentTheirProblemCodesUnderTheirStatuses() {
        JsonNode post = generated.path("paths").path("/orders").path("post");
        assertThat(post.path("operationId").asText()).isEqualTo("placeOrder");
        JsonNode postResponses = post.path("responses");
        assertThat(fieldNames(postResponses))
                .containsExactlyInAnyOrder("200", "201", "400", "401", "402", "404", "409", "410", "500");
        assertThat(postResponses.path("402").path("description").asText()).isEqualTo("PAYMENT_FAILED");
        assertThat(postResponses.path("404").path("description").asText()).isEqualTo("HOLD_NOT_FOUND");
        assertThat(postResponses.path("409").path("description").asText()).isEqualTo("SEAT_TAKEN");
        assertThat(postResponses.path("410").path("description").asText()).isEqualTo("HOLD_EXPIRED");

        JsonNode get = generated.path("paths").path("/orders/{id}").path("get");
        assertThat(get.path("operationId").asText()).isEqualTo("getOrder");
        assertThat(fieldNames(get.path("responses"))).containsExactlyInAnyOrder("200", "400", "401", "404", "500");
        assertThat(get.path("responses").path("404").path("description").asText()).isEqualTo("ORDER_NOT_FOUND");
    }

    @Test
    void holdEndpointsDocumentTheirProblemCodesUnderTheirStatuses() {
        JsonNode post = generated.path("paths").path("/events/{id}/holds").path("post").path("responses");
        assertThat(fieldNames(post)).containsExactlyInAnyOrder("200", "201", "400", "401", "404", "409", "500");
        assertThat(post.path("409").path("description").asText())
                .contains("SEAT_TAKEN", "LIMIT_EXCEEDED", "SALE_NOT_OPEN");

        JsonNode delete = generated.path("paths").path("/holds/{id}").path("delete").path("responses");
        assertThat(fieldNames(delete)).containsExactlyInAnyOrder("204", "400", "401", "404", "409", "500");
        assertThat(delete.path("404").path("description").asText()).isEqualTo("HOLD_NOT_FOUND");
        assertThat(delete.path("409").path("description").asText()).isEqualTo("HOLD_CONVERTED");

        JsonNode problem = generated.path("components").path("schemas").path("Problem").path("properties");
        assertThat(problem.path("takenAgoMs").path("format").asText()).isEqualTo("int64");
        assertThat(problem.path("seatIds").path("items").path("format").asText()).isEqualTo("uuid");
        assertThat(problem.path("limit").path("type").asText()).isEqualTo("integer");
    }

    @Test
    void problemSchemaHasTheCodePropertyWithEveryErrorCode() {
        JsonNode problem = generated.path("components").path("schemas").path("Problem");
        JsonNode codeEnum = problem.path("properties").path("code").path("enum");

        assertThat(fieldNames(problem.path("properties")))
                .contains("type", "title", "status", "detail", "instance", "code", "errors", "errorId");
        assertThat(StreamSupport.stream(codeEnum.spliterator(), false).map(JsonNode::asText).toList())
                .containsExactlyInAnyOrderElementsOf(Arrays.stream(ErrorCode.values()).map(Enum::name).toList());
        assertThat(StreamSupport.stream(problem.path("required").spliterator(), false).map(JsonNode::asText))
                .contains("code");
    }

    @Test
    void errorResponsesAreProblemJsonUnderTheirCodesStatus() {
        JsonNode responses = generated.path("paths").path("/events/{id}").path("get").path("responses");

        assertThat(fieldNames(responses)).containsExactlyInAnyOrder("200", "400", "404", "500");
        assertThat(responses.path("404").path("description").asText()).isEqualTo("EVENT_NOT_FOUND");
        assertThat(responses.path("404").path("content").path("application/problem+json")
                .path("schema").path("$ref").asText()).isEqualTo("#/components/schemas/Problem");
        assertThat(responses.path("500").path("description").asText()).isEqualTo("INTERNAL_ERROR");
    }

    /** Otherwise the generated TypeScript types would make every response field optional. */
    @Test
    void responseFieldsAreRequiredAndJson() {
        JsonNode schemas = generated.path("components").path("schemas");
        for (String name : List.of("EventList", "EventSummary", "EventDetail", "PriceTier", "VenueRef",
                "TokenResponse", "UserResponse", "HoldResponse")) {
            assertThat(StreamSupport.stream(schemas.path(name).path("required").spliterator(), false)
                    .map(JsonNode::asText).toList())
                    .as("required properties of %s", name)
                    .containsExactlyInAnyOrderElementsOf(fieldNames(schemas.path(name).path("properties")));
        }
        // Request schemas keep the required list derived from their validation annotations.
        assertThat(schemas.path("LoginRequest").path("required").toString()).isEqualTo("[\"email\",\"password\"]");
        // RegisterRequest.password is validated by a custom constraint springdoc can't read, so it is
        // documented explicitly (M0.7); the frontend's generated type must not make it optional.
        assertThat(schemas.path("RegisterRequest").path("required").toString())
                .isEqualTo("[\"email\",\"password\"]");
        assertThat(schemas.path("RegisterRequest").path("properties").path("password").path("minLength").asInt())
                .isEqualTo(12);

        JsonNode ok = generated.path("paths").path("/events/{id}").path("get").path("responses").path("200");
        assertThat(fieldNames(ok.path("content"))).containsExactly("application/json");
    }

    private static List<String> fieldNames(JsonNode node) {
        return StreamSupport.stream(((Iterable<String>) node::fieldNames).spliterator(), false).toList();
    }
}
