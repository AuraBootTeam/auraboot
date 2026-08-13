package com.auraboot.module.meta.excel.mapper;

import com.auraboot.module.meta.excel.entity.ImportJob;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisMapperAnnotationBuilder;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class ImportJobMapperBootstrapTest {

    @AfterEach
    void clearEntityMetadata() {
        TableInfoHelper.remove(ImportJob.class);
    }

    @Test
    void annotationMapperRegistersAutoResultMapAndStatements() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        assistant.setCurrentNamespace(ImportJobMapper.class.getName());
        TableInfoHelper.initTableInfo(assistant, ImportJob.class);

        assertThatCode(() -> new MybatisMapperAnnotationBuilder(
                configuration, ImportJobMapper.class).parse()).doesNotThrowAnyException();
        assertThat(configuration.hasResultMap(
                ImportJobMapper.class.getName() + ".mybatis-plus_ImportJob")).isTrue();
        assertThat(configuration.hasStatement(
                ImportJobMapper.class.getName() + ".findExpiredReports", false)).isTrue();
        assertThat(configuration.hasStatement(
                ImportJobMapper.class.getName() + ".clearErrorReport", false)).isTrue();
    }
}
