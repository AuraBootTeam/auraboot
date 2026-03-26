package com.auraboot.framework.application.bootstrap.seeder;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.i18n.entity.I18nResource;
import com.auraboot.framework.i18n.mapper.I18nResourceMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class I18nBaseSeeder {
    private final I18nResourceMapper i18nResourceMapper;
    private final ObjectMapper objectMapper;

    public void seed() {
        try {
            ClassPathResource resource = new ClassPathResource("seed/i18n-base.json");
            InputStream is = resource.getInputStream();
            List<Map<String, String>> entries = objectMapper.readValue(is, new TypeReference<>() {});

            List<I18nResource> resources = new ArrayList<>();
            for (Map<String, String> entry : entries) {
                String key = entry.get("key");
                for (Map.Entry<String, String> langEntry : entry.entrySet()) {
                    if ("key".equals(langEntry.getKey())) continue;
                    I18nResource r = new I18nResource();
                    r.setPid(UniqueIdGenerator.generate());
                    r.setTenantId(0L);
                    r.setI18nKey(key);
                    r.setLang(langEntry.getKey());
                    r.setValue(langEntry.getValue());
                    r.setSource("system");
                    r.setStatus(I18nResource.STATUS_APPROVED);
                    resources.add(r);
                }
            }

            if (!resources.isEmpty()) {
                int inserted = 0;
                int batchSize = 100;
                for (int i = 0; i < resources.size(); i += batchSize) {
                    List<I18nResource> batch = resources.subList(i, Math.min(i + batchSize, resources.size()));
                    inserted += i18nResourceMapper.batchInsertIgnore(batch);
                }
                log.info("I18nBaseSeeder: seeded {} i18n resources ({} total, {} already existed)",
                        inserted, resources.size(), resources.size() - inserted);
            } else {
                log.info("I18nBaseSeeder: no entries found in seed/i18n-base.json");
            }
        } catch (Exception e) {
            log.error("I18nBaseSeeder: failed to seed i18n data", e);
            throw new RuntimeException("Failed to seed i18n base data", e);
        }
    }
}
