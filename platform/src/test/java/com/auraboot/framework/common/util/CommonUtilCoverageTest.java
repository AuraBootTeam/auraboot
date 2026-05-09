package com.auraboot.framework.common.util;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.tenant.service.TenantPreferenceService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests covering the simple util classes in {@code com.auraboot.framework.common.util}.
 * Pure logic + edge cases; no Spring context.
 */
@ExtendWith(MockitoExtension.class)
class CommonUtilCoverageTest {

    // -------- ClassUtil --------
    @Test
    void classUtil_classLoaders() {
        assertThat(ClassUtil.getContextClassLoader()).isNotNull();
        assertThat(ClassUtil.getFallbackClassLoader()).isNotNull();
    }

    // -------- NamingStyleConvertUtil --------
    @Test
    void namingStyle_camelToSnake_handlesNullEmptyAndCamelCase() {
        assertThat(NamingStyleConvertUtil.camelToSnake(null)).isNull();
        assertThat(NamingStyleConvertUtil.camelToSnake("")).isEqualTo("");
        assertThat(NamingStyleConvertUtil.camelToSnake("camelCase")).isEqualTo("camel_case");
        assertThat(NamingStyleConvertUtil.camelToSnake("HelloWorld")).isEqualTo("hello_world");
        assertThat(NamingStyleConvertUtil.camelToSnake("a")).isEqualTo("a");
        assertThat(NamingStyleConvertUtil.camelToSnake("ABC")).isEqualTo("a_b_c");
    }

    @Test
    void namingStyle_snakeToCamel_handlesNullEmptyAndSnakeCase() {
        assertThat(NamingStyleConvertUtil.snakeToCamel(null)).isNull();
        assertThat(NamingStyleConvertUtil.snakeToCamel("")).isEqualTo("");
        assertThat(NamingStyleConvertUtil.snakeToCamel("snake_case")).isEqualTo("snakeCase");
        assertThat(NamingStyleConvertUtil.snakeToCamel("a_b_c")).isEqualTo("aBC");
        assertThat(NamingStyleConvertUtil.snakeToCamel("plain")).isEqualTo("plain");
    }

    // -------- StringUtil --------
    @Test
    void stringUtil_isBlankIsEmptyVariations() {
        assertThat(StringUtil.isEmpty(null)).isTrue();
        assertThat(StringUtil.isEmpty("")).isTrue();
        assertThat(StringUtil.isEmpty("x")).isFalse();
        assertThat(StringUtil.isNotEmpty("x")).isTrue();
        assertThat(StringUtil.isNotEmpty(null)).isFalse();

        assertThat(StringUtil.isBlank(null)).isTrue();
        assertThat(StringUtil.isBlank("")).isTrue();
        assertThat(StringUtil.isBlank("   \t\n")).isTrue();
        assertThat(StringUtil.isBlank("x")).isFalse();
        assertThat(StringUtil.isNotBlank("x")).isTrue();
        assertThat(StringUtil.isNotBlank("   ")).isFalse();
    }

    @Test
    void stringUtil_truncateBasic() {
        assertThat(StringUtil.truncate("abcdef", 3)).isEqualTo("abc");
        assertThat(StringUtil.truncate("ab", 5)).isEqualTo("ab");
    }

    @Test
    void stringUtil_truncateWithIndicator() {
        assertThat(StringUtil.truncate("abcdef", 5, "...")).isEqualTo("ab...");
        assertThat(StringUtil.truncate("ab", 5, "...")).isEqualTo("ab");
        assertThatThrownBy(() -> StringUtil.truncate("abc", 3, "..."))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stringUtil_randomReturnsRequestedLength() {
        assertThat(StringUtil.random(10)).hasSize(10);
        assertThat(StringUtil.random(0)).isEmpty();
    }

    // -------- ByteUtil --------
    @Test
    void byteUtil_swapEndianRoundtrip() {
        byte[] input = new byte[16];
        for (int i = 0; i < 16; i++) {
            input[i] = (byte) i;
        }
        byte[] once = ByteUtil.swapEndian(input);
        assertThat(once).hasSize(16);
        // Swap twice should return to the original
        byte[] twice = ByteUtil.swapEndian(once);
        assertThat(twice).isEqualTo(input);
    }

    // -------- DateUtil --------
    @Test
    void dateUtil_currentInstantNotNull() {
        assertThat(DateUtil.getCurrentInstant()).isNotNull();
        assertThat(DateUtil.getCurrentLocalDateTimeUtc()).isNotNull();
    }

    @Test
    void dateUtil_conversionsAndNullHandling() {
        Instant instant = Instant.parse("2024-01-02T03:04:05Z");
        LocalDateTime ldt = DateUtil.toUtcLocalDateTime(instant);
        assertThat(ldt).isEqualTo(LocalDateTime.of(2024, 1, 2, 3, 4, 5));
        assertThat(DateUtil.toUtcInstant(ldt)).isEqualTo(instant);
        assertThat(DateUtil.toUtcLocalDateTime(null)).isNull();
        assertThat(DateUtil.toUtcInstant(null)).isNull();
    }

    // -------- TenantClock --------
    @Mock
    TenantPreferenceService tenantPreferenceService;

    @Test
    void tenantClock_defaultZoneWhenTenantNull() {
        TenantClock clock = new TenantClock(tenantPreferenceService);
        assertThat(clock.getZoneId(null)).isEqualTo(ZoneId.of("Asia/Shanghai"));
    }

    @Test
    void tenantClock_defaultZoneWhenPreferenceMissing() {
        TenantClock clock = new TenantClock(tenantPreferenceService);
        when(tenantPreferenceService.getPreference(1L, "ui.timezone")).thenReturn(null);
        assertThat(clock.getZoneId(1L)).isEqualTo(ZoneId.of("Asia/Shanghai"));
    }

    @Test
    void tenantClock_defaultZoneWhenPreferenceNullNode() {
        TenantClock clock = new TenantClock(tenantPreferenceService);
        when(tenantPreferenceService.getPreference(2L, "ui.timezone")).thenReturn(NullNode.getInstance());
        assertThat(clock.getZoneId(2L)).isEqualTo(ZoneId.of("Asia/Shanghai"));
    }

    @Test
    void tenantClock_defaultZoneWhenPreferenceBlank() {
        TenantClock clock = new TenantClock(tenantPreferenceService);
        JsonNode node = JsonNodeFactory.instance.textNode("   ");
        when(tenantPreferenceService.getPreference(3L, "ui.timezone")).thenReturn(node);
        assertThat(clock.getZoneId(3L)).isEqualTo(ZoneId.of("Asia/Shanghai"));
    }

    @Test
    void tenantClock_resolvesConfiguredZone() {
        TenantClock clock = new TenantClock(tenantPreferenceService);
        JsonNode node = JsonNodeFactory.instance.textNode("UTC");
        when(tenantPreferenceService.getPreference(4L, "ui.timezone")).thenReturn(node);
        assertThat(clock.getZoneId(4L)).isEqualTo(ZoneId.of("UTC"));
    }

    @Test
    void tenantClock_fallbackWhenInvalidZoneThrows() {
        TenantClock clock = new TenantClock(tenantPreferenceService);
        JsonNode node = JsonNodeFactory.instance.textNode("Not/AZone");
        when(tenantPreferenceService.getPreference(5L, "ui.timezone")).thenReturn(node);
        assertThat(clock.getZoneId(5L)).isEqualTo(ZoneId.of("Asia/Shanghai"));
    }

    @Test
    void tenantClock_businessDateAndDateTime() {
        TenantClock clock = new TenantClock(tenantPreferenceService);
        lenient().when(tenantPreferenceService.getPreference(6L, "ui.timezone"))
                .thenReturn(JsonNodeFactory.instance.textNode("UTC"));
        LocalDate d = clock.businessDate(6L);
        ZonedDateTime zdt = clock.businessDateTime(6L);
        assertThat(d).isNotNull();
        assertThat(zdt).isNotNull();
        assertThat(zdt.getZone()).isEqualTo(ZoneId.of("UTC"));
    }

    // -------- UlidGenerator / UniqueIdGenerator --------
    @Test
    void ulidGenerator_generateAndValidate() {
        String id = UlidGenerator.generate();
        assertThat(id).hasSize(26);
        assertThat(UlidGenerator.isValid(id)).isTrue();
        assertThat(UlidGenerator.isValid(null)).isFalse();
        assertThat(UlidGenerator.isValid("short")).isFalse();
        assertThat(UlidGenerator.isValid("##########################")).isFalse();
        assertThat(UlidGenerator.nextULID()).hasSize(26);
        assertThat(UlidGenerator.generate(System.currentTimeMillis())).hasSize(26);
    }

    @Test
    void uniqueIdGenerator_generateAndValidate() {
        String id = UniqueIdGenerator.generate();
        assertThat(id).hasSize(26);
        assertThat(UniqueIdGenerator.isValid(id)).isTrue();
        assertThat(UniqueIdGenerator.isValid(null)).isFalse();
        assertThat(UniqueIdGenerator.isValid("short")).isFalse();
        assertThat(UniqueIdGenerator.isValid("##########################")).isFalse();
    }

    // -------- ThreadLocalUtil --------
    @Test
    void threadLocalUtil_setGetRemove() {
        ThreadLocalUtil.set("hello");
        assertThat(ThreadLocalUtil.get()).isEqualTo("hello");
        ThreadLocalUtil.remove(null);
        assertThat(ThreadLocalUtil.get()).isNull();
    }

    // -------- LambdaUtil --------
    static class LambdaTarget {
        private String name;
        private String value;

        public String getName() { return name; }
        public String getValue() { return value; }
        public String fetch() { return name; }
    }

    @Test
    void lambdaUtil_resolvesGetterFieldName() {
        SFunction<LambdaTarget, ?> f = LambdaTarget::getName;
        assertThat(LambdaUtil.getFieldName(f)).isEqualTo("name");
        SFunction<LambdaTarget, ?> g = LambdaTarget::getValue;
        assertThat(LambdaUtil.getFieldName(g)).isEqualTo("value");
    }

    @Test
    void lambdaUtil_returnsRawNameForNonGetter() {
        SFunction<LambdaTarget, ?> f = LambdaTarget::fetch;
        assertThat(LambdaUtil.getFieldName(f)).isEqualTo("fetch");
    }

    // -------- JsonUtil --------
    static class Bean {
        public String a;
        public Integer b;
    }

    @Test
    void jsonUtil_parseAndToJson() {
        assertThat(JsonUtil.getObjectMapper()).isNotNull();
        Bean b = JsonUtil.parse("{\"a\":\"x\",\"b\":1}", Bean.class);
        assertThat(b.a).isEqualTo("x");
        assertThat(b.b).isEqualTo(1);
        String json = JsonUtil.toJson(b);
        assertThat(json).contains("\"a\":\"x\"");
    }

    @Test
    void jsonUtil_parseInvalidThrowsBusinessException() {
        assertThatThrownBy(() -> JsonUtil.parse("not-json", Bean.class))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> JsonUtil.parse("oops", new TypeReference<Map<String, Object>>() {}))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> JsonUtil.readTree("###"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void jsonUtil_typeReferenceParseAndReadTree() {
        Map<String, Object> map = JsonUtil.parse("{\"a\":1}", new TypeReference<Map<String, Object>>() {});
        assertThat(map).containsEntry("a", 1);
        JsonNode node = JsonUtil.readTree("{\"a\":1}");
        assertThat(node.get("a").asInt()).isEqualTo(1);
    }

    @Test
    void jsonUtil_convertValueAndToMap() {
        Bean b = new Bean();
        b.a = "x";
        b.b = 2;
        Map<String, Object> map = JsonUtil.toMap(b);
        assertThat(map).containsKeys("a", "b");
        assertThat(JsonUtil.toMap(null)).isNull();
        Map<String, Object> existing = Map.of("k", "v");
        assertThat(JsonUtil.toMap(existing)).isSameAs(existing);
        Map<String, Object> converted = JsonUtil.convertValue(b, new TypeReference<Map<String, Object>>() {});
        assertThat(converted).containsKey("a");
    }

    @Test
    void jsonUtil_toJsonWithSortedKeysOmitsNullsAndOrders() {
        Bean b = new Bean();
        b.a = null;
        b.b = 7;
        String json = JsonUtil.toJsonWithSortedKeys(b);
        assertThat(json).doesNotContain("\"a\"");
        assertThat(json).contains("\"b\":7");
    }

    // -------- YamlUtil --------
    @Test
    void yamlUtil_parseFromUrl() throws Exception {
        Path tmp = Files.createTempFile("yaml-util-test", ".yaml");
        Files.writeString(tmp, "a: hi\nb: 5\n");
        URL url = tmp.toUri().toURL();
        Bean parsed = YamlUtil.parse(url, Bean.class);
        assertThat(parsed.a).isEqualTo("hi");
        assertThat(parsed.b).isEqualTo(5);
        Files.deleteIfExists(tmp);
    }

    @Test
    void yamlUtil_invalidUrlThrowsBusinessException() throws Exception {
        URL bad = new File("/non/existent/path/yaml-util-missing.yaml").toURI().toURL();
        assertThatThrownBy(() -> YamlUtil.parse(bad, Bean.class))
                .isInstanceOf(BusinessException.class);
    }
}
