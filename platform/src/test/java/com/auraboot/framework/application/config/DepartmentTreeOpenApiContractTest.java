package com.auraboot.framework.application.config;

import com.auraboot.framework.organization.dto.DepartmentTreeNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.util.Json31;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DepartmentTreeOpenApiContractTest {
    @Test
    void rootAndLeafSerializationRetainNullableParentAndConcreteChildrenWithoutInventedSortOrder() {
        var child = new DepartmentTreeNode("child-pid", "Child", "root-pid", 2, List.of());
        var root = new DepartmentTreeNode("root-pid", "Root", null, 0, List.of(child));
        var json = new ObjectMapper().valueToTree(root);
        assertThat(json.size()).isEqualTo(5);
        assertThat(json.has("sortOrder")).isFalse();
        assertThat(json.get("parentPid").isNull()).isTrue();
        assertThat(json.at("/children/0/parentPid").asText()).isEqualTo("root-pid");
        assertThat(json.at("/children/0/employeeCount").asInt()).isEqualTo(2);
        assertThat(json.at("/children/0/children").isArray()).isTrue();
        assertThat(json.at("/children/0/children").isEmpty()).isTrue();
    }

    @Test
    void schemaDescribesEverySerializedFieldAndRecursiveNullableRootParent() {
        var schema = new ModelConverters(true).readAll(DepartmentTreeNode.class).get("DepartmentTreeNode");
        assertThat(schema.getRequired()).containsExactlyInAnyOrder("pid", "name", "parentPid", "employeeCount", "children");
        var json = Json31.mapper().valueToTree(schema);
        assertThat(json.at("/properties/parentPid/type").toString()).contains("null");
        assertThat(json.at("/properties/pid/type").toString()).doesNotContain("null");
        assertThat(json.at("/properties/children/items/$ref").asText()).endsWith("/DepartmentTreeNode");
        assertThat(json.at("/properties/employeeCount/type").asText()).isEqualTo("integer");
        assertThat(json.at("/properties").has("sortOrder")).isFalse();
    }
}
