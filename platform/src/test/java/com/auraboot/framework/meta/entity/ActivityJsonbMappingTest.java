package com.auraboot.framework.meta.entity;

import com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ActivityJsonbMappingTest {

    @Test
    void metadataFieldUsesJsonbMapping() throws NoSuchFieldException {
        TableName tableName = Activity.class.getAnnotation(TableName.class);
        assertThat(tableName).isNotNull();
        assertThat(tableName.value()).isEqualTo("ab_activity");
        assertThat(tableName.autoResultMap()).isTrue();

        TableField metadata = Activity.class.getDeclaredField("metadata").getAnnotation(TableField.class);
        assertThat(metadata).isNotNull();
        assertThat(metadata.value()).isEqualTo("metadata");
        assertThat(metadata.jdbcType()).isEqualTo(JdbcType.OTHER);
        assertThat(metadata.typeHandler()).isEqualTo(JsonbStringTypeHandler.class);
    }
}
