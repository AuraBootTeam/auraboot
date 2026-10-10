package com.auraboot.framework.openplatform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static com.auraboot.framework.openplatform.service.OpenApiResourceContract.*;
import static org.assertj.core.api.Assertions.*;

class OpenApiContractArtifactsTest {
    private final ObjectMapper json = new ObjectMapper();
    private Field value(String source, Type type, boolean read, boolean write, boolean required, boolean nullable) {
        return new Field(source, type, read, write, required, nullable, Set.of(), false, null, Map.of(), null);
    }
    private OpenApiResourceContract resource() {
        var fields = new LinkedHashMap<String, Field>();
        fields.put("pid", value("pid", Type.PID, true, false, false, false));
        fields.put("title", new Field("internal_name", Type.STRING, true, true, true, false, Set.of("EQ", "IN", "IS_NULL"), true, null, Map.of(), null));
        fields.put("cost", value("internal_cost", Type.DECIMAL, true, true, false, true));
        fields.put("profile", new Field("internal_profile", Type.OBJECT, true, true, false, false, Set.of(), false, null,
                Map.of("label", value("internal_label", Type.STRING, true, true, true, false), "secret", value("private_input", Type.STRING, false, true, false, true)), null));
        fields.put("tags", new Field("internal_tags", Type.ARRAY, true, true, false, false, Set.of(), false, null, Map.of(), value("item", Type.STRING, true, true, false, true)));
        fields.put("inputOnly", value("opaque_input", Type.STRING, false, true, false, true));
        fields.put("day", value("internal_day", Type.DATE, true, false, false, false));
        fields.put("at", value("internal_at", Type.DATETIME, true, false, false, false));
        fields.put("count", value("internal_count", Type.INTEGER, true, false, false, false));
        fields.put("flag", value("internal_flag", Type.BOOLEAN, true, false, false, false));
        var scopes = new EnumMap<Operation, String>(Operation.class);
        for (Operation op : Operation.values()) scopes.put(op, "parts." + op.code());
        return new OpenApiResourceContract("fixture.parts", "private_internal_model", 3, Set.of(Operation.values()), scopes,
                DataPolicy.TENANT_WIDE, fields, 7, 4, 5000, Set.of("created", "updated", "deleted"));
    }
    private OpenApiQueryContract query() {
        return new OpenApiQueryContract("fixture.summary", "private_query_source", 2, "a".repeat(64), Map.of("execute", "parts.summary"),
                Map.of("private_internal_model", "fixture.parts"),
                Map.of("zones", new OpenApiQueryContract.Parameter(Type.ARRAY, true, false, value("item", Type.STRING, false, true, false, false))),
                Map.of("count", value("private_total", Type.INTEGER, true, false, false, false)), 7, 30, 5000, 2);
    }
    private JsonNode tree(Object value) { return json.valueToTree(value); }

    @Test void schemasUsePublicAliasesWithDistinctReadCreateAndPatchContracts() {
        var doc = tree(OpenApiContractArtifacts.resource(resource()));
        assertThat(doc.path("openapi").asText()).isEqualTo("3.1.0");
        assertThat(doc.toString()).doesNotContain("private_internal_model", "internal_name", "private_input", "opaque_input");
        var schemas = doc.path("components").path("schemas");
        assertThat(schemas.path("Resource").path("required").toString()).isEqualTo("[\"pid\"]");
        assertThat(schemas.path("Resource").path("properties").has("inputOnly")).isFalse();
        assertThat(schemas.path("Resource").path("properties").path("profile").path("properties").has("secret")).isFalse();
        assertThat(schemas.path("Resource").path("properties").path("profile").has("required")).isFalse();
        assertThat(schemas.path("CreateInput").path("required").toString()).isEqualTo("[\"title\"]");
        assertThat(schemas.path("UpdateInput").has("required")).isFalse();
        assertThat(schemas.path("UpdateInput").path("properties").path("profile").path("required").toString()).isEqualTo("[\"label\"]");
        assertThat(schemas.path("UpdateInput").path("properties").has("day")).isFalse();
        assertThat(schemas.path("CreateInput").path("additionalProperties").asBoolean()).isFalse();
    }
    @Test void everyDeclaredOperationCarriesScopeAndWritePreconditionsAndDeleteHasNoBody() {
        var doc = tree(OpenApiContractArtifacts.resource(resource()));
        var collection = doc.path("paths").path("/api/open/v1/resources/fixture.parts");
        var record = doc.path("paths").path("/api/open/v1/resources/fixture.parts/{recordPid}");
        assertThat(collection.size()).isEqualTo(2); assertThat(record.size()).isEqualTo(3);
        assertThat(collection.path("post").path("security").get(0).path("oauth2ClientCredentials").get(0).asText()).isEqualTo("parts.create");
        assertThat(record.path("patch").path("parameters").toString()).contains("If-Match", "Idempotency-Key", "recordPid");
        assertThat(record.path("delete").path("responses").path("204").has("content")).isFalse();
        assertThat(collection.path("post").path("responses").path("201").path("headers").has("Location")).isTrue();
        assertThat(doc.path("webhooks").size()).isEqualTo(3);
        var notification = doc.path("webhooks").elements().next().path("post");
        assertThat(notification.path("parameters").get(4).path("name").asText()).isEqualTo("X-Webhook-Signature");
        assertThat(notification.path("parameters").get(4).path("required").asBoolean()).isTrue();
        assertThat(notification.path("description").asText()).contains("mandatory HMAC");
        assertThat(doc.path("components").path("schemas").path("ModelEvent").path("properties").path("schemaVersion").path("const").asInt()).isEqualTo(3);
    }
    @Test void queryArtifactsHideSourceBindingsAndKeepRequiredTypedParametersAndBudget() {
        var doc = tree(OpenApiContractArtifacts.query(query()));
        assertThat(doc.toString()).doesNotContain("private_query_source", "private_internal_model", "private_total", "a".repeat(64));
        assertThat(doc.path("components").path("schemas").path("QueryParameters").path("required").toString()).isEqualTo("[\"zones\"]");
        assertThat(doc.path("components").path("schemas").path("QueryItem").path("properties").has("count")).isTrue();
        var endpoint = doc.path("paths").path("/api/open/v1/queries/fixture.summary:execute").path("post");
        assertThat(endpoint.path("x-max-concurrent").asInt()).isEqualTo(2);
        assertThat(endpoint.path("x-max-rows").asInt()).isEqualTo(30);
        assertThat(OpenApiContractArtifacts.queryTypes(query())).contains("\"zones\": Array<string>", "\"count\"?: number").doesNotContain("private_");
    }
    @Test void readOnlyPublicationsDoNotAdvertiseWritesOrWriteTypes() {
        var source = resource();
        var read = new OpenApiResourceContract(source.code(), source.modelCode(), source.schemaVersion(), Set.of(Operation.GET),
                Map.of(Operation.GET, "parts.get"), source.dataPolicy(), source.fields(), 7, 4, 5000, Set.of());
        var doc = tree(OpenApiContractArtifacts.resource(read));
        assertThat(doc.path("paths").size()).isEqualTo(1);
        assertThat(doc.path("components").path("schemas").has("CreateInput")).isFalse();
        assertThat(OpenApiContractArtifacts.resourceTypes(read)).doesNotContain("CreateInput", "UpdateInput", "ModelEvent");
    }
    @Test void actualDocumentsAndDeclarationsAreExportedForIndependentConsumerValidation() throws Exception {
        var folder = Path.of("build/test-artifacts/open-api-contract"); Files.createDirectories(folder);
        json.writeValue(folder.resolve("resource.json").toFile(), OpenApiContractArtifacts.resource(resource()));
        json.writeValue(folder.resolve("query.json").toFile(), OpenApiContractArtifacts.query(query()));
        Files.writeString(folder.resolve("resource.d.ts"), OpenApiContractArtifacts.resourceTypes(resource()));
        Files.writeString(folder.resolve("query.d.ts"), OpenApiContractArtifacts.queryTypes(query()));
        assertThat(Files.size(folder.resolve("resource.json"))).isGreaterThan(100);
        assertThat(OpenApiContractArtifacts.resourceTypes(resource())).contains("\"title\": string", "\"pid\": string", "schemaVersion: 3").doesNotContain("internal_", "private_input");
    }
}
