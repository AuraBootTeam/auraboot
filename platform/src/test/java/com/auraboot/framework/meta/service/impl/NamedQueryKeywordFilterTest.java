package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.entity.NamedQueryField;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class NamedQueryKeywordFilterTest {
    private NamedQueryField field(String name, boolean searchable, String... operators) {
        var f = new NamedQueryField(); f.setFieldCode(name); f.setColumnExpr(name);
        f.setSearchable(searchable); f.setOperators(operators); return f;
    }
    @Test void onlySearchableFieldsWithPermittedPatternOperatorsParticipate() {
        var clauses=new ArrayList<String>(); var params=new HashMap<String,Object>();
        NamedQueryKeywordFilter.append("x' OR 1=1 --", List.of(field("business_code",true),
                field("secret",false),field("restricted",true,"eq")),clauses,params);
        assertThat(clauses).hasSize(1);
        assertThat(clauses.getFirst()).contains("CAST(business_code AS TEXT)")
                .doesNotContain("secret", "restricted", "x' OR 1=1");
        assertThat(params.get("__aura_nq_keyword")).isEqualTo("%x' OR 1=1 --%");
    }
    @Test void noSearchableFieldsCannotTurnSearchIntoAnUnfilteredRead() {
        var clauses=new ArrayList<String>(); var params=new HashMap<String,Object>();
        NamedQueryKeywordFilter.append("missing",List.of(field("code",false)),clauses,params);
        assertThat(clauses).containsExactly("1 = 0"); assertThat(params).isEmpty();
    }
    @Test void blankKeywordKeepsExistingFiltersAndParameters() {
        var clauses=new ArrayList<>(List.of("tenant_id = 1")); var params=new HashMap<String,Object>();
        params.put("tenantId",1L);
        NamedQueryKeywordFilter.append("  ",List.of(field("code",true)),clauses,params);
        assertThat(clauses).containsExactly("tenant_id = 1"); assertThat(params).containsOnly(entry("tenantId",1L));
    }
    @Test void searchableMaskedAliasCannotLeakThroughMatchesOrCounts() {
        var clauses=new ArrayList<String>();var params=new HashMap<String,Object>();
        NamedQueryKeywordFilter.append("secret",List.of(field("private_field",true)),clauses,params,
                Set.of("private_field"));
        assertThat(clauses).containsExactly("1 = 0");assertThat(params).isEmpty();
    }

}
