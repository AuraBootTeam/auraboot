package com.auraboot.framework.plugin.mapper;

import com.auraboot.framework.plugin.entity.PluginRecord;
import com.auraboot.framework.plugin.typehandler.PluginManifestTypeHandler;
import com.auraboot.framework.plugin.dto.PluginManifest;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.sql.ResultSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real mapper statement metadata and its JSONB decoder; no database or application context. */
class PluginRecordMapperProjectionTest {
    private MybatisConfiguration configuration() {
        TableInfoHelper.remove(PluginRecord.class);
        var configuration=new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(PluginRecordMapper.class);
        return configuration;
    }
    @AfterEach void clearEntityMetadata() {TableInfoHelper.remove(PluginRecord.class);}

    @Test void pidLookupUsesTheCanonicalEntityResultMapAndKeepsItsExactPredicate() {
        var configuration=configuration();
        var byPid=configuration.getMappedStatement(PluginRecordMapper.class.getName()+".findByPid");
        var canonical=configuration.getMappedStatement(PluginRecordMapper.class.getName()+".selectById").getResultMaps().getFirst();
        assertThat(byPid.getResultMaps().getFirst().getId()).isEqualTo(canonical.getId());
        assertThat(canonical.getId()).isEqualTo(PluginRecordMapper.class.getName()+".mybatis-plus_PluginRecord");
        assertThat(byPid.getBoundSql(java.util.Map.of("pid","owned-plugin")).getSql())
                .contains("WHERE pid = ? AND deleted_flag = false");
        assertThat(byPid.getResultMaps().getFirst().getResultMappings()).anySatisfy(mapping->{
            assertThat(mapping.getProperty()).isEqualTo("manifest");
            assertThat(mapping.getTypeHandler()).isInstanceOf(PluginManifestTypeHandler.class);
        });
    }

    @Test void selectedPidStatementDecodesManifestDependencyInsteadOfSilentlyDroppingJsonb() throws Exception {
        var configuration=configuration();
        var mapping=configuration.getMappedStatement(PluginRecordMapper.class.getName()+".findByPid")
                .getResultMaps().getFirst().getResultMappings().stream()
                .filter(field->field.getProperty().equals("manifest")).findFirst().orElseThrow();
        var resultSet=mock(ResultSet.class);
        when(resultSet.getString("manifest")).thenReturn("{\"pluginId\":\"com.auraboot.quote-procurement\",\"dependencies\":[\"com.auraboot.quote-engine\"]}");
        var manifest=(PluginManifest)mapping.getTypeHandler().getResult(resultSet,"manifest");
        assertThat(manifest).isNotNull();
        assertThat(manifest.getPluginId()).isEqualTo("com.auraboot.quote-procurement");
        assertThat(manifest.getDependencies()).containsExactly("com.auraboot.quote-engine");
        verify(resultSet).getString("manifest");
    }
}
