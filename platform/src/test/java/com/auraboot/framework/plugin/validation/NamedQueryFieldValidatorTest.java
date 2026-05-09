package com.auraboot.framework.plugin.validation;

import com.auraboot.framework.meta.dto.NamedQueryFieldRequest;
import com.auraboot.framework.plugin.dto.imports.NamedQueryDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NamedQueryFieldValidatorTest {

    private NamedQueryFieldValidator validator;

    @BeforeEach
    void setUp() {
        validator = new NamedQueryFieldValidator();
    }

    private PluginValidationContext ctx(PluginManifestExtended m) {
        return PluginValidationContext.builder().manifest(m).build();
    }

    private NamedQueryDefinitionDTO query(String code, List<NamedQueryFieldRequest> fields) {
        NamedQueryDefinitionDTO q = new NamedQueryDefinitionDTO();
        q.setCode(code);
        q.setFromSql("SELECT * FROM ab_user");
        q.setFields(fields);
        return q;
    }

    private NamedQueryFieldRequest field(String code, String expr, String type) {
        NamedQueryFieldRequest f = new NamedQueryFieldRequest();
        f.setFieldCode(code);
        f.setColumnExpr(expr);
        f.setDataType(type);
        return f;
    }

    @Test
    void category_isSemantic() {
        assertThat(validator.category()).isEqualTo("semantic");
    }

    @Test
    void noQueries_returnsEmpty() {
        PluginManifestExtended m = new PluginManifestExtended();
        assertThat(validator.validate(ctx(m))).isEmpty();
    }

    @Test
    void validQuery_noMessages() {
        PluginManifestExtended m = new PluginManifestExtended();
        m.setNamedQueries(List.of(query("ns_q", List.of(field("name", "u.name", "string")))));
        assertThat(validator.validate(ctx(m))).isEmpty();
    }

    @Test
    void missingFieldCode_emitsError() {
        PluginManifestExtended m = new PluginManifestExtended();
        m.setNamedQueries(List.of(query("ns_q", List.of(field(null, "u.name", "string")))));
        var msgs = validator.validate(ctx(m));
        assertThat(msgs).anyMatch(x -> "S-NQF-CODE".equals(x.getCode()) && x.isError());
    }

    @Test
    void invalidFieldCodeFormat_emitsError() {
        PluginManifestExtended m = new PluginManifestExtended();
        m.setNamedQueries(List.of(query("ns_q", List.of(field("123bad", "u.name", "string")))));
        var msgs = validator.validate(ctx(m));
        assertThat(msgs).anyMatch(x -> "S-NQF-CODE-FMT".equals(x.getCode()));
    }

    @Test
    void duplicateFieldCodes_emitsError() {
        List<NamedQueryFieldRequest> fields = List.of(
                field("a", "u.a", "string"),
                field("a", "u.a", "string"));
        PluginManifestExtended m = new PluginManifestExtended();
        m.setNamedQueries(List.of(query("ns_q", fields)));
        var msgs = validator.validate(ctx(m));
        assertThat(msgs).anyMatch(x -> "S-NQF-DUP".equals(x.getCode()));
    }

    @Test
    void missingColumnExpr_emitsError() {
        PluginManifestExtended m = new PluginManifestExtended();
        m.setNamedQueries(List.of(query("ns_q", List.of(field("name", null, "string")))));
        var msgs = validator.validate(ctx(m));
        assertThat(msgs).anyMatch(x -> "S-NQF-EXPR".equals(x.getCode()));
    }

    @Test
    void missingDataType_emitsError() {
        PluginManifestExtended m = new PluginManifestExtended();
        m.setNamedQueries(List.of(query("ns_q", List.of(field("name", "u.name", null)))));
        var msgs = validator.validate(ctx(m));
        assertThat(msgs).anyMatch(x -> "S-NQF-TYPE".equals(x.getCode()));
    }

    @Test
    void invalidDataType_emitsError() {
        PluginManifestExtended m = new PluginManifestExtended();
        m.setNamedQueries(List.of(query("ns_q", List.of(field("name", "u.name", "weird")))));
        var msgs = validator.validate(ctx(m));
        assertThat(msgs).anyMatch(x -> "S-NQF-TYPE-VAL".equals(x.getCode()));
    }

    @Test
    void caseInsensitiveDataType_accepted() {
        PluginManifestExtended m = new PluginManifestExtended();
        m.setNamedQueries(List.of(query("ns_q", List.of(field("name", "u.name", "STRING")))));
        var msgs = validator.validate(ctx(m));
        assertThat(msgs).noneMatch(x -> "S-NQF-TYPE-VAL".equals(x.getCode()));
    }

    @Test
    void emptyOrNullFieldList_noMessages() {
        PluginManifestExtended m = new PluginManifestExtended();
        m.setNamedQueries(List.of(
                query("q1", new ArrayList<>()),
                query("q2", null)));
        assertThat(validator.validate(ctx(m))).isEmpty();
    }
}
