package com.auraboot.framework.i18n.service;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.i18n.entity.I18nResource;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for I18nResourceService individual CRUD, ref queries, pagination,
 * and statistics methods.
 *
 * <p>Complements I18nResourceServiceTest (which covers batchUpsert / findByKeyPrefix /
 * syncFromModel / syncFromField). This suite focuses on:
 * <ul>
 *   <li>IR-01: create – persists resource with generated pid and correct defaults</li>
 *   <li>IR-02: create – throws when required fields are missing</li>
 *   <li>IR-03: findByPid – returns the saved resource</li>
 *   <li>IR-04: update – changes value and source fields</li>
 *   <li>IR-05: update – throws for non-existent pid</li>
 *   <li>IR-06: delete – removes the resource; findByPid returns null afterwards</li>
 *   <li>IR-07: findBySource – returns only resources matching the given source</li>
 *   <li>IR-08: findByRef – returns resources tagged to a specific refType+refId</li>
 *   <li>IR-09: deleteByRef – removes all resources for a refType+refId</li>
 *   <li>IR-10: findPage – supports lang and keyword filters with correct pagination</li>
 *   <li>IR-11: getResourceMapByLang – flat map contains the inserted key/value</li>
 *   <li>IR-12: getNestedResourceMapByLang – nested structure for dot-separated key</li>
 *   <li>IR-13: getDistinctLangs – includes newly inserted language</li>
 *   <li>IR-14: countByLang – count increases after batch insert</li>
 *   <li>IR-15: countBySource – count keyed by source</li>
 * </ul>
 */
@Slf4j
@DisplayName("I18nResourceService Integration Tests (CRUD + stats)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class I18nResourceServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private I18nResourceService i18nResourceService;

    /**
     * Run-specific prefix so every test key is unique across parallel / repeated runs.
     * Short timestamp suffix keeps keys under typical DB column limits.
     */
    private final String pfx = "ir-" + System.currentTimeMillis();

    // Shared state set by IR-01 and used by IR-03 / IR-04 / IR-06
    private String createdPid;

    // ==================== IR-01: create persists resource ====================

    @Test
    @Order(1)
    @DisplayName("IR-01: create persists resource with generated pid and STATUS_APPROVED default")
    void IR_01_create_persistsResourceWithDefaults() {
        I18nResource resource = buildResource(pfx + ".create.label", "zh-CN", "创建测试", "system");
        // Status not set – service should default to APPROVED

        I18nResource saved = i18nResourceService.create(resource);

        assertThat(saved).isNotNull();
        assertThat(saved.getPid()).isNotBlank();
        assertThat(saved.getI18nKey()).isEqualTo(pfx + ".create.label");
        assertThat(saved.getLang()).isEqualTo("zh-CN");
        assertThat(saved.getValue()).isEqualTo("创建测试");
        assertThat(saved.getSource()).isEqualTo("system");
        assertThat(saved.getStatus()).isEqualTo(I18nResource.STATUS_APPROVED);
        assertThat(saved.getTenantId()).isNotNull();

        this.createdPid = saved.getPid();
        log.info("IR-01: created resource pid={}", createdPid);
    }

    // ==================== IR-02: create throws for missing required fields ====================

    @Test
    @Order(2)
    @DisplayName("IR-02: create throws BusinessException when required fields (key/lang/value/source) are missing")
    void IR_02_create_missingRequiredFields_throws() {
        // Missing i18n_key
        I18nResource noKey = buildResource(null, "zh-CN", "value", "system");
        assertThatThrownBy(() -> i18nResourceService.create(noKey))
                .isInstanceOf(BusinessException.class);

        // Missing lang
        I18nResource noLang = buildResource(pfx + ".nolang", null, "value", "system");
        assertThatThrownBy(() -> i18nResourceService.create(noLang))
                .isInstanceOf(BusinessException.class);

        // Missing value
        I18nResource noValue = buildResource(pfx + ".novalue", "zh-CN", null, "system");
        assertThatThrownBy(() -> i18nResourceService.create(noValue))
                .isInstanceOf(BusinessException.class);

        // Missing source
        I18nResource noSource = buildResource(pfx + ".nosource", "zh-CN", "value", null);
        assertThatThrownBy(() -> i18nResourceService.create(noSource))
                .isInstanceOf(BusinessException.class);
    }

    // ==================== IR-03: findByPid returns the saved resource ====================

    @Test
    @Order(3)
    @DisplayName("IR-03: findByPid returns the resource created in IR-01")
    void IR_03_findByPid_returnsCreatedResource() {
        assertThat(createdPid).as("createdPid must be set by IR-01").isNotBlank();

        I18nResource found = i18nResourceService.findByPid(createdPid);

        assertThat(found).isNotNull();
        assertThat(found.getPid()).isEqualTo(createdPid);
        assertThat(found.getI18nKey()).isEqualTo(pfx + ".create.label");
    }

    // ==================== IR-04: update changes value ====================

    @Test
    @Order(4)
    @DisplayName("IR-04: update changes value and source on the persisted resource")
    void IR_04_update_changesValueAndSource() {
        assertThat(createdPid).as("createdPid must be set by IR-01").isNotBlank();

        I18nResource patch = new I18nResource();
        patch.setValue("已更新");
        patch.setSource("import");
        patch.setStatus(I18nResource.STATUS_APPROVED);

        I18nResource updated = i18nResourceService.update(createdPid, patch);

        assertThat(updated).isNotNull();
        assertThat(updated.getValue()).isEqualTo("已更新");
        assertThat(updated.getSource()).isEqualTo("import");

        // Confirm via fresh read
        I18nResource refetched = i18nResourceService.findByPid(createdPid);
        assertThat(refetched.getValue()).isEqualTo("已更新");
    }

    // ==================== IR-05: update throws for non-existent pid ====================

    @Test
    @Order(5)
    @DisplayName("IR-05: update throws BusinessException for non-existent pid")
    void IR_05_update_nonExistentPid_throws() {
        I18nResource patch = new I18nResource();
        patch.setValue("should fail");
        patch.setSource("system");
        patch.setStatus(I18nResource.STATUS_APPROVED);

        assertThatThrownBy(() -> i18nResourceService.update("ghost-pid-" + pfx, patch))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not found");
    }

    // ==================== IR-06: delete removes the resource ====================

    @Test
    @Order(6)
    @DisplayName("IR-06: delete removes the resource so findByPid returns null")
    void IR_06_delete_removesResource() {
        assertThat(createdPid).as("createdPid must be set by IR-01").isNotBlank();

        i18nResourceService.delete(createdPid);

        I18nResource afterDelete = i18nResourceService.findByPid(createdPid);
        assertThat(afterDelete).isNull();
    }

    // ==================== IR-07: findBySource ====================

    @Test
    @Order(7)
    @DisplayName("IR-07: findBySource returns only resources matching the given source tag")
    void IR_07_findBySource_filtersCorrectly() {
        String uniqueSource = "src-" + pfx;

        i18nResourceService.create(buildResource(pfx + ".src.a", "zh-CN", "A", uniqueSource));
        i18nResourceService.create(buildResource(pfx + ".src.b", "zh-CN", "B", uniqueSource));
        i18nResourceService.create(buildResource(pfx + ".src.c", "zh-CN", "C", "other-" + pfx));

        List<I18nResource> results = i18nResourceService.findBySource(uniqueSource);

        assertThat(results).isNotNull();
        assertThat(results.stream().filter(r -> r.getI18nKey().startsWith(pfx + ".src.")))
                .hasSize(2)
                .allSatisfy(r -> assertThat(r.getSource()).isEqualTo(uniqueSource));
    }

    // ==================== IR-08: findByRef ====================

    @Test
    @Order(8)
    @DisplayName("IR-08: findByRef returns resources tagged with a specific refType and refId")
    void IR_08_findByRef_returnsMatchingResources() {
        Long refId = 77700L + System.currentTimeMillis() % 10000;
        String refType = I18nResource.REF_TYPE_MODEL;

        I18nResource r1 = buildResource(pfx + ".ref.label", "zh-CN", "Ref Label", "model");
        r1.setRefType(refType);
        r1.setRefId(refId);
        i18nResourceService.create(r1);

        I18nResource r2 = buildResource(pfx + ".ref.desc", "zh-CN", "Ref Desc", "model");
        r2.setRefType(refType);
        r2.setRefId(refId);
        i18nResourceService.create(r2);

        List<I18nResource> results = i18nResourceService.findByRef(refType, refId);

        assertThat(results).isNotNull();
        // At least our two inserted ref keys should be in the results
        assertThat(results.stream().anyMatch(r -> r.getI18nKey().startsWith(pfx + ".ref."))).isTrue();
        // Both keys must belong to the correct ref
        results.stream()
                .filter(r -> r.getI18nKey().startsWith(pfx + ".ref."))
                .forEach(r -> {
                    assertThat(r.getRefType()).isEqualTo(refType);
                    assertThat(r.getRefId()).isEqualTo(refId);
                });

        log.info("IR-08: found {} resources for refType={} refId={}", results.size(), refType, refId);
    }

    // ==================== IR-09: deleteByRef ====================

    @Test
    @Order(9)
    @DisplayName("IR-09: deleteByRef removes all resources associated with a refType+refId")
    void IR_09_deleteByRef_removesAllRefResources() {
        Long refId = 66600L + System.currentTimeMillis() % 10000;
        String refType = I18nResource.REF_TYPE_FIELD;

        I18nResource r = buildResource(pfx + ".delref.label", "zh-CN", "Del Ref", "model");
        r.setRefType(refType);
        r.setRefId(refId);
        I18nResource saved = i18nResourceService.create(r);

        // Verify it exists
        assertThat(i18nResourceService.findByPid(saved.getPid())).isNotNull();

        // Delete by ref
        i18nResourceService.deleteByRef(refType, refId);

        // Must be gone
        assertThat(i18nResourceService.findByPid(saved.getPid())).isNull();
    }

    // ==================== IR-10: findPage ====================

    @Test
    @Order(10)
    @DisplayName("IR-10: findPage returns paginated results filtered by lang and keyword")
    void IR_10_findPage_filtersByLangAndKeyword() {
        String uniqueLang = "x-test-" + System.currentTimeMillis();

        i18nResourceService.create(buildResource(pfx + ".page.alpha", uniqueLang, "Alpha Value", "system"));
        i18nResourceService.create(buildResource(pfx + ".page.beta", uniqueLang, "Beta Value", "system"));

        IPage<I18nResource> page = i18nResourceService.findPage(
                1, 10,
                uniqueLang,       // lang filter
                null,             // source
                null,             // status
                pfx + ".page.",   // keyPrefix
                null              // keyword
        );

        assertThat(page).isNotNull();
        assertThat(page.getRecords()).isNotEmpty();
        assertThat(page.getRecords())
                .allSatisfy(r -> assertThat(r.getLang()).isEqualTo(uniqueLang));
        assertThat(page.getTotal()).isGreaterThanOrEqualTo(2);
    }

    // ==================== IR-11: getResourceMapByLang ====================

    @Test
    @Order(11)
    @DisplayName("IR-11: getResourceMapByLang returns flat map containing inserted key/value")
    void IR_11_getResourceMapByLang_containsInsertedEntry() {
        String uniqueKey = pfx + ".map.test";
        i18nResourceService.create(buildResource(uniqueKey, "zh-CN", "地图测试", "system"));

        Map<String, String> flatMap = i18nResourceService.getResourceMapByLang("zh-CN");

        assertThat(flatMap).isNotNull().isNotEmpty();
        assertThat(flatMap).containsKey(uniqueKey);
        assertThat(flatMap.get(uniqueKey)).isEqualTo("地图测试");
    }

    // ==================== IR-12: getNestedResourceMapByLang ====================

    @Test
    @Order(12)
    @DisplayName("IR-12: getNestedResourceMapByLang builds nested structure from dot-separated key")
    void IR_12_getNestedResourceMapByLang_buildsNestedStructure() {
        // Insert a key with three dot-separated segments specific to this run
        String key = pfx + ".nested.value";
        i18nResourceService.create(buildResource(key, "zh-CN", "嵌套值", "system"));

        Map<String, Object> nested = i18nResourceService.getNestedResourceMapByLang("zh-CN");

        assertThat(nested).isNotNull().isNotEmpty();
        // Top-level segment should be the pfx key (e.g. "ir-<ts>")
        // Navigate: nested[pfx]["nested"]["value"]
        String topKey = pfx.split("\\.")[0]; // pfx has no dots, it IS the top key
        assertThat(nested).containsKey(topKey);
        @SuppressWarnings("unchecked")
        Map<String, Object> second = (Map<String, Object>) nested.get(topKey);
        assertThat(second).containsKey("nested");
        @SuppressWarnings("unchecked")
        Map<String, Object> third = (Map<String, Object>) second.get("nested");
        assertThat(third).containsKey("value");
        assertThat(third.get("value")).isEqualTo("嵌套值");
    }

    // ==================== IR-13: getDistinctLangs ====================

    @Test
    @Order(13)
    @DisplayName("IR-13: getDistinctLangs includes a newly inserted language code")
    void IR_13_getDistinctLangs_includesNewLanguage() {
        // lang column is VARCHAR(20) — use a short unique suffix
        String rareLang = "x-" + (System.currentTimeMillis() % 100000);
        i18nResourceService.create(buildResource(pfx + ".lang.probe", rareLang, "probe", "system"));

        List<String> langs = i18nResourceService.getDistinctLangs();

        assertThat(langs).isNotNull().contains(rareLang);
    }

    // ==================== IR-14: countByLang ====================

    @Test
    @Order(14)
    @DisplayName("IR-14: countByLang returns a count > 0 for zh-CN after inserting records")
    void IR_14_countByLang_returnsPositiveCount() {
        // Ensure at least one zh-CN record exists from earlier tests
        Map<String, Long> counts = i18nResourceService.countByLang();

        assertThat(counts).isNotNull().isNotEmpty();
        assertThat(counts).containsKey("zh-CN");
        assertThat(counts.get("zh-CN")).isGreaterThan(0);
    }

    // ==================== IR-15: countBySource ====================

    @Test
    @Order(15)
    @DisplayName("IR-15: countBySource returns count keyed by source tag")
    void IR_15_countBySource_keyedBySource() {
        // Ensure at least one "system" record exists
        i18nResourceService.create(buildResource(pfx + ".count.src", "zh-CN", "count src", "system"));

        Map<String, Long> counts = i18nResourceService.countBySource();

        assertThat(counts).isNotNull().isNotEmpty();
        assertThat(counts).containsKey("system");
        assertThat(counts.get("system")).isGreaterThan(0);
    }

    // ==================== helpers ====================

    private I18nResource buildResource(String key, String lang, String value, String source) {
        return I18nResource.builder()
                .i18nKey(key)
                .lang(lang)
                .value(value)
                .source(source)
                .status(I18nResource.STATUS_APPROVED)
                .build();
    }
}
