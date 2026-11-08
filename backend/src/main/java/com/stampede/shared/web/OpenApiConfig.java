package com.stampede.shared.web;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;

import com.stampede.shared.error.ErrorCode;
import com.stampede.shared.error.ErrorResponses;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.servers.Server;

/**
 * The parts of the OpenAPI spec that springdoc can't infer from the controllers (D-010): the
 * problem+json error shape, which error codes each operation returns, and that response fields are
 * always present.
 *
 * <p>The generated spec is committed at {@code docs/openapi.json}; {@code OpenApiSpecTest} fails when
 * the code and that file disagree. The runtime endpoint {@code /v3/api-docs} is off unless the
 * {@code openapi} profile is active (application.yml).
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    static final String PROBLEM_SCHEMA = "Problem";
    static final String PROBLEM_JSON = "application/problem+json";

    @Bean
    OpenAPI stampedeOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Stampede API")
                        .version("0.1.0")
                        .description("Errors are RFC 7807 problem+json; clients branch on the `code` property."))
                // Fixed, relative server: without it springdoc writes the request's host and port into
                // the spec, which would change on every test run. The frontend calls the API same-origin.
                .servers(List.of(new Server().url("/").description("Same origin as the frontend")))
                .components(new Components()
                        .addSchemas(PROBLEM_SCHEMA, problemSchema())
                        .addSchemas("FieldError", fieldErrorSchema()));
    }

    /**
     * Adds a problem+json response per status for the codes in {@link ErrorResponses}, plus 500
     * INTERNAL_ERROR on every operation. The status comes from {@link ErrorCode#status()}, so the
     * spec can't disagree with what the code actually returns.
     */
    @Bean
    OperationCustomizer problemResponses() {
        return (operation, handlerMethod) -> {
            ErrorResponses declared = handlerMethod.getMethodAnnotation(ErrorResponses.class);
            Map<HttpStatus, List<ErrorCode>> byStatus = Arrays.stream(
                            declared == null ? new ErrorCode[0] : declared.value())
                    .collect(Collectors.groupingBy(ErrorCode::status, TreeMap::new, Collectors.toList()));
            byStatus.computeIfAbsent(HttpStatus.INTERNAL_SERVER_ERROR, s -> new ArrayList<>())
                    .add(ErrorCode.INTERNAL_ERROR);

            byStatus.forEach((status, codes) -> operation.getResponses().addApiResponse(
                    String.valueOf(status.value()),
                    new ApiResponse()
                            .description(codes.stream().map(Enum::name).collect(Collectors.joining(", ")))
                            .content(new Content().addMediaType(PROBLEM_JSON, new MediaType()
                                    .schema(new Schema<>().$ref("#/components/schemas/" + PROBLEM_SCHEMA))))));
            return operation;
        };
    }

    /**
     * Marks every property of a response-only schema as required. Jackson writes every field of our
     * response records, null or not, so each property is always present; without this, the generated
     * TypeScript types would make every field optional. Schemas also used in request bodies keep the
     * required list springdoc derives from their validation annotations. Problem has its own list
     * (detail, errors and errorId are genuinely optional).
     */
    @Bean
    OpenApiCustomizer responsePropertiesAreRequired() {
        return openApi -> {
            @SuppressWarnings("rawtypes")
            Map<String, Schema> schemas = openApi.getComponents().getSchemas();
            Set<String> inResponses = new HashSet<>();
            Set<String> inRequests = new HashSet<>();
            openApi.getPaths().values().forEach(path -> path.readOperations().forEach(operation -> {
                if (operation.getRequestBody() != null) {
                    collectSchemaNames(operation.getRequestBody().getContent(), schemas, inRequests);
                }
                operation.getResponses().values()
                        .forEach(response -> collectSchemaNames(response.getContent(), schemas, inResponses));
            }));
            inResponses.removeAll(inRequests);
            for (String name : inResponses) {
                Schema<?> schema = schemas.get(name);
                if (schema != null && schema.getProperties() != null) {
                    schema.setRequired(new ArrayList<>(new TreeSet<>(schema.getProperties().keySet())));
                }
            }
        };
    }

    /** Adds the names of all component schemas reachable from this content (except Problem). */
    @SuppressWarnings("rawtypes")
    private static void collectSchemaNames(Content content, Map<String, Schema> schemas, Set<String> names) {
        if (content != null) {
            content.values().forEach(mediaType -> collectSchemaNames(mediaType.getSchema(), schemas, names));
        }
    }

    @SuppressWarnings("rawtypes")
    private static void collectSchemaNames(Schema<?> schema, Map<String, Schema> schemas, Set<String> names) {
        if (schema == null) {
            return;
        }
        if (schema.get$ref() != null) {
            String name = schema.get$ref().substring(schema.get$ref().lastIndexOf('/') + 1);
            if (!name.equals(PROBLEM_SCHEMA) && names.add(name)) {
                collectSchemaNames(schemas.get(name), schemas, names);
            }
            return;
        }
        collectSchemaNames(schema.getItems(), schemas, names);
        if (schema.getProperties() != null) {
            schema.getProperties().values().forEach(property -> collectSchemaNames(property, schemas, names));
        }
    }

    private static Schema<?> problemSchema() {
        StringSchema code = new StringSchema();
        code.setEnum(Arrays.stream(ErrorCode.values()).map(Enum::name).toList());
        code.description("Stable error code (PRD section 10). Branch on this, never on title or detail.");
        return new ObjectSchema()
                .description("RFC 7807 problem details, served as application/problem+json.")
                .addProperty("type", new StringSchema().description("Always about:blank for now."))
                .addProperty("title", new StringSchema())
                .addProperty("status", new IntegerSchema().format("int32"))
                .addProperty("detail", new StringSchema().description("Human-readable; may change."))
                .addProperty("instance", new StringSchema().description("The request path."))
                .addProperty("code", code)
                .addProperty("errors", new ArraySchema()
                        .items(new Schema<>().$ref("#/components/schemas/FieldError"))
                        .description("VALIDATION_FAILED on a request body: one entry per invalid field."))
                .addProperty("errorId", new StringSchema()
                        .description("INTERNAL_ERROR only: the id the server logged the error under."))
                .required(List.of("title", "status", "code"));
    }

    private static Schema<?> fieldErrorSchema() {
        return new ObjectSchema()
                .addProperty("field", new StringSchema())
                .addProperty("message", new StringSchema())
                .required(List.of("field", "message"));
    }
}
