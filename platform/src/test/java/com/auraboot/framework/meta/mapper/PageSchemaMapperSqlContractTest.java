package com.auraboot.framework.meta.mapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PageSchema mapper SQL contract")
class PageSchemaMapperSqlContractTest {

    private static final Path PAGE_SCHEMA_MAPPER =
            Path.of("src/main/java/com/auraboot/framework/meta/mapper/PageSchemaMapper.java");

    @Test
    @DisplayName("does not select star from ab_page_schema")
    void doesNotSelectStarFromPageSchema() throws Exception {
        String source = Files.readString(PAGE_SCHEMA_MAPPER);

        assertThat(Pattern.compile("(?is)select\\s+\\*\\s+from\\s+ab_page_schema(?!_history)")
                .matcher(source)
                .find())
                .as("SELECT * on ab_page_schema breaks PostgreSQL cached plans after bootstrap/setup DDL")
                .isFalse();
    }
}
