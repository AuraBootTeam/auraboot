package com.auraboot.framework.eventpolicy;

import com.auraboot.framework.eventpolicy.mapper.DrtPolicyExecLogMapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DrtPolicyExecLogQueryTest {
    @Test
    void optionalTraceFiltersNeverBindUntypedNullPredicatesOrScanWithoutALink() throws Exception {
        var method=DrtPolicyExecLogMapper.class.getMethod("findByTraceLink",Long.class,String.class,String.class);
        String template=String.join("\n",method.getAnnotation(Select.class).value());
        var source=new XMLLanguageDriver().createSqlSource(new Configuration(),template,Map.class);
        var parameters=new HashMap<String,Object>();parameters.put("tenantId",7L);
        String empty=source.getBoundSql(parameters).getSql();assertTrue(empty.contains("AND 1 = 0"));
        parameters.put("correlationId","sla:owned");
        var correlation=source.getBoundSql(parameters);assertFalse(correlation.getSql().contains("IS NOT NULL"));
        assertEquals(java.util.List.of("tenantId","correlationId"),correlation.getParameterMappings().stream().map(p->p.getProperty()).toList());
        parameters.remove("correlationId");parameters.put("decisionTraceId","owned-trace");
        assertEquals(java.util.List.of("tenantId","decisionTraceId"),source.getBoundSql(parameters).getParameterMappings().stream().map(p->p.getProperty()).toList());
        parameters.put("correlationId","sla:owned");assertTrue(source.getBoundSql(parameters).getSql().contains(" OR "));
    }
}
