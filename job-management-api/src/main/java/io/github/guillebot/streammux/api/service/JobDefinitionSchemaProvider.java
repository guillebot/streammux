package io.github.guillebot.streammux.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.github.guillebot.streammux.contracts.model.JobDefinition;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.converter.ResolvedSchema;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.media.Schema;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Single source of truth for the JobDefinition JSON Schema.
 *
 * <p>The schema is derived from the {@link JobDefinition} record (and every schema it references)
 * via swagger-core's {@code ModelConverters}, which is the same machinery springdoc uses to build
 * the OpenAPI document. That output is post-processed into a self-contained JSON Schema 2020-12
 * document with {@code $defs} instead of {@code #/components/schemas/...} references and with
 * {@code additionalProperties: false} tightened onto every object so typos surface immediately.
 *
 * <p>The document is served verbatim by {@code GET /jobs/schema} and compiled once into a
 * {@link JsonSchema} used to pre-validate incoming payloads on the write paths.
 */
@Component
public class JobDefinitionSchemaProvider {

    private static final String SCHEMA_ID = "https://streammux.optimum.net/schemas/job-definition.schema.json";
    private static final String SCHEMA_DIALECT = "https://json-schema.org/draft/2020-12/schema";
    private static final String ROOT_DEF = "JobDefinition";

    private final ObjectMapper objectMapper;
    private final JsonNode schemaJson;
    private final JsonSchema compiledSchema;

    public JobDefinitionSchemaProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.schemaJson = buildSchemaDocument();
        this.compiledSchema = compile(this.schemaJson);
    }

    /** Returns the immutable JSON Schema document served to clients and used internally. */
    public JsonNode getSchemaJson() {
        return schemaJson;
    }

    /**
     * Validates a raw payload against the compiled JSON Schema. Throws
     * {@link IllegalArgumentException} whose message enumerates every violation with a JSON
     * pointer so the shared {@code ApiExceptionHandler} maps it to a {@code VALIDATION_ERROR}.
     */
    public void validate(JsonNode payload) {
        Set<ValidationMessage> errors = compiledSchema.validate(payload);
        if (errors.isEmpty()) return;
        String combined = errors.stream()
            .map(this::formatMessage)
            .sorted()
            .collect(Collectors.joining("; "));
        throw new IllegalArgumentException(combined);
    }

    private String formatMessage(ValidationMessage message) {
        // networknt exposes both the schema-relative pointer (e.g. /jobId) and a human message
        // that already includes the pointer; use the message directly so callers see "$.jobId:
        // string found, integer expected" and don't have to know the internal shape.
        return message.getMessage();
    }

    private JsonNode buildSchemaDocument() {
        ResolvedSchema resolved = ModelConverters.getInstance().readAllAsResolvedSchema(JobDefinition.class);
        if (resolved == null || resolved.schema == null) {
            throw new IllegalStateException("swagger-core did not resolve a schema for " + JobDefinition.class.getName());
        }

        ObjectMapper swaggerMapper = Json31.mapper();
        Map<String, Schema> combined = new TreeMap<>();
        combined.put(ROOT_DEF, resolved.schema);
        if (resolved.referencedSchemas != null) {
            combined.putAll(resolved.referencedSchemas);
        }

        ObjectNode defs = objectMapper.createObjectNode();
        for (Map.Entry<String, Schema> entry : combined.entrySet()) {
            JsonNode converted = swaggerMapper.valueToTree(entry.getValue());
            if (converted instanceof ObjectNode object) {
                rewriteRefs(object);
                tightenAdditionalProperties(object);
                allowNullOnProperties(object);
                // Root stays strict (a null body is not a JobDefinition). Other defs are
                // reached via $ref and may hold null on the wire (Jackson emits unused config
                // components as null), so widen the def's own type to include "null". Keeping
                // the $ref bare (see widenNullable) preserves nested per-property errors.
                if (!ROOT_DEF.equals(entry.getKey())) {
                    widenObjectTypeToIncludeNull(object);
                }
                defs.set(entry.getKey(), object);
            } else {
                defs.set(entry.getKey(), converted);
            }
        }

        ObjectNode root = objectMapper.createObjectNode();
        root.put("$schema", SCHEMA_DIALECT);
        root.put("$id", SCHEMA_ID);
        root.put("title", "JobDefinition");
        root.put("$ref", "#/$defs/" + ROOT_DEF);
        root.set("$defs", defs);
        return root;
    }

    private void rewriteRefs(JsonNode node) {
        if (node instanceof ObjectNode object) {
            JsonNode ref = object.get("$ref");
            if (ref != null && ref.isTextual()) {
                String value = ref.asText();
                if (value.startsWith("#/components/schemas/")) {
                    object.put("$ref", "#/$defs/" + value.substring("#/components/schemas/".length()));
                }
            }
            object.fields().forEachRemaining(field -> rewriteRefs(field.getValue()));
        } else if (node instanceof ArrayNode array) {
            for (JsonNode child : array) {
                rewriteRefs(child);
            }
        }
    }

    private void tightenAdditionalProperties(ObjectNode object) {
        JsonNode type = object.get("type");
        JsonNode properties = object.get("properties");
        boolean hasProperties = properties != null && properties.isObject();
        boolean isObject = hasProperties || isObjectType(type);

        // Only touch schemas that describe a closed object shape. Free-form maps
        // (Map<String,String> labels, Map<String,Object> alarm mappings, streamProperties)
        // set additionalProperties themselves to a schema, not a boolean; leave those alone.
        if (isObject && hasProperties && object.get("additionalProperties") == null) {
            object.put("additionalProperties", false);
        }

        // Recurse into nested schema-bearing nodes so oneOf/anyOf/allOf/items branches are covered.
        for (String key : new String[] {"items", "not"}) {
            JsonNode child = object.get(key);
            if (child instanceof ObjectNode childObj) {
                tightenAdditionalProperties(childObj);
            }
        }
        for (String key : new String[] {"oneOf", "anyOf", "allOf"}) {
            JsonNode child = object.get(key);
            if (child instanceof ArrayNode array) {
                for (JsonNode entry : array) {
                    if (entry instanceof ObjectNode entryObj) {
                        tightenAdditionalProperties(entryObj);
                    }
                }
            }
        }
        if (properties instanceof ObjectNode propObj) {
            propObj.fields().forEachRemaining(field -> {
                if (field.getValue() instanceof ObjectNode nested) {
                    tightenAdditionalProperties(nested);
                }
            });
        }
    }

    /**
     * Allow {@code null} on every property so Jackson's default emit-nulls behaviour for nullable
     * record components (e.g. {@code randomSamplerConfig} when unset) doesn't trip structural
     * validation. Presence is enforced by {@link io.github.guillebot.streammux.contracts.validation.JobDefinitionValidator}
     * afterwards, so this only widens the shape check, never the business rules.
     */
    private boolean isObjectType(JsonNode type) {
        if (type == null) return false;
        if (type.isTextual()) return "object".equals(type.asText());
        if (type.isArray()) {
            for (JsonNode t : type) {
                if (t.isTextual() && "object".equals(t.asText())) return true;
            }
        }
        return false;
    }

    private void allowNullOnProperties(ObjectNode object) {
        JsonNode properties = object.get("properties");
        if (properties instanceof ObjectNode propObj) {
            propObj.fields().forEachRemaining(field -> {
                if (field.getValue() instanceof ObjectNode nested) {
                    widenNullable(nested);
                }
            });
        }
    }

    private void widenNullable(ObjectNode node) {
        JsonNode ref = node.get("$ref");
        if (ref != null && ref.isTextual()) {
            // Leave the $ref bare. Wrapping it in anyOf/oneOf collapses nested
            // `additionalProperties` violations into a generic "no branch matched" error.
            // Nullability comes from the target def's own `type: ["object","null"]`
            // (see buildSchemaDocument).
            return;
        }
        JsonNode type = node.get("type");
        if (type == null) {
            // Leave untyped/combinator-only schemas (oneOf/anyOf/allOf) alone.
            return;
        }
        if (type.isTextual()) {
            if (!"null".equals(type.asText())) {
                ArrayNode types = objectMapper.createArrayNode();
                types.add(type.asText());
                types.add("null");
                node.set("type", types);
            }
        } else if (type.isArray()) {
            boolean hasNull = false;
            for (JsonNode t : type) {
                if (t.isTextual() && "null".equals(t.asText())) {
                    hasNull = true;
                    break;
                }
            }
            if (!hasNull) {
                ((ArrayNode) type).add("null");
            }
        }
    }

    /**
     * Adds {@code "null"} to a definition's own {@code type} so a bare {@code $ref} to it
     * accepts null on the wire. Object defs missing an explicit {@code type} get
     * {@code ["object", "null"]}; textual/array types are widened in place.
     */
    private void widenObjectTypeToIncludeNull(ObjectNode object) {
        JsonNode type = object.get("type");
        JsonNode properties = object.get("properties");
        boolean looksLikeObject = properties != null && properties.isObject();
        if (type == null) {
            if (!looksLikeObject) return;
            ArrayNode types = objectMapper.createArrayNode();
            types.add("object");
            types.add("null");
            object.set("type", types);
            return;
        }
        if (type.isTextual()) {
            if ("null".equals(type.asText())) return;
            ArrayNode types = objectMapper.createArrayNode();
            types.add(type.asText());
            types.add("null");
            object.set("type", types);
            return;
        }
        if (type.isArray()) {
            for (JsonNode t : type) {
                if (t.isTextual() && "null".equals(t.asText())) return;
            }
            ((ArrayNode) type).add("null");
        }
    }

    private JsonSchema compile(JsonNode schemaNode) {
        SchemaValidatorsConfig config = new SchemaValidatorsConfig();
        // Networknt reads $schema from the doc, but pin the spec explicitly so pre-2020-12
        // draft references (should any slip in from a future swagger-core upgrade) don't
        // silently downgrade validation.
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(schemaNode, config);
    }
}
