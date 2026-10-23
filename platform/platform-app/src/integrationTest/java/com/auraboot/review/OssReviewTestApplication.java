package com.auraboot.review;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.filter.TypeFilter;
import java.io.IOException;

/** Production components without unrelated integration tests' private @Configuration classes. */
@SpringBootApplication
@ComponentScan(basePackages = {"com.auraboot.framework", "com.auraboot.module"}, excludeFilters =
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = OssReviewTestApplication.TestSources.class))
@org.mybatis.spring.annotation.MapperScan({"com.auraboot.framework.**.mapper",
        "com.auraboot.framework.*.dao", "com.auraboot.framework.application.release",
        "com.auraboot.module.*.mapper", "com.auraboot.module.meta.excel.mapper"})
@org.springframework.transaction.annotation.EnableTransactionManagement
public class OssReviewTestApplication {
    public static class TestSources implements TypeFilter {
        @Override public boolean match(MetadataReader reader, MetadataReaderFactory factory) throws IOException {
            String source = reader.getResource().getURL().toString();
            return source.contains("/classes/java/test/") || source.contains("/classes/java/integrationTest/")
                    || java.util.Set.of("com.auraboot.framework.application.MetaApplication",
                        "com.auraboot.framework.application.CoreOnlyApplication",
                        "com.auraboot.framework.agent.AiRuntimeAutoConfiguration")
                        .contains(reader.getClassMetadata().getClassName());
        }
    }
}
