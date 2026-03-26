package com.auraboot.framework.i18n.mapper;

import com.auraboot.framework.i18n.entity.I18nResource;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

/**
 * I18n Resource Mapper
 *
 * @author AuraBoot
 */
@Mapper
public interface I18nResourceMapper extends BaseMapper<I18nResource> {

    // ==================== Basic Query ====================

    /**
     * Find by PID
     */
    @Select("SELECT * FROM ab_i18n_resource WHERE pid = #{pid} AND deleted_flag = false")
    I18nResource selectByPid(@Param("pid") String pid);

    /**
     * Find by key and language
     */
    @Select("SELECT * FROM ab_i18n_resource WHERE tenant_id = #{tenantId} AND i18n_key = #{key} AND lang = #{lang} AND deleted_flag = false")
    I18nResource selectByKeyAndLang(
        @Param("tenantId") Long tenantId,
        @Param("key") String key,
        @Param("lang") String lang
    );

    /**
     * Find all values for a key (all languages)
     */
    @Select("SELECT * FROM ab_i18n_resource WHERE tenant_id = #{tenantId} AND i18n_key = #{key} AND deleted_flag = false")
    List<I18nResource> selectByKey(@Param("tenantId") Long tenantId, @Param("key") String key);

    // ==================== Batch Query ====================

    /**
     * Find all resources for a language (for compilation)
     */
    @Select("SELECT * FROM ab_i18n_resource WHERE tenant_id = #{tenantId} AND lang = #{lang} AND status = 'approved' AND deleted_flag = false")
    List<I18nResource> selectAllByLang(@Param("tenantId") Long tenantId, @Param("lang") String lang);

    /**
     * Find all approved resources for a language across all tenants (excluding tenant_id = 0).
     * Used by public /api/i18n/{locale} endpoint where no tenant context is available.
     */
    @Select("SELECT * FROM ab_i18n_resource WHERE tenant_id != 0 AND lang = #{lang} AND status = 'approved' AND deleted_flag = false")
    List<I18nResource> selectAllByLangAllTenants(@Param("lang") String lang);

    /**
     * Find resources by key prefix (scope query)
     * e.g., "model.device" returns all keys starting with "model.device"
     */
    @Select("SELECT * FROM ab_i18n_resource WHERE tenant_id = #{tenantId} AND lang = #{lang} AND i18n_key LIKE CONCAT(#{prefix}, '%') AND status = 'approved' AND deleted_flag = false")
    List<I18nResource> selectByKeyPrefix(
        @Param("tenantId") Long tenantId,
        @Param("lang") String lang,
        @Param("prefix") String prefix
    );

    /**
     * Find resources by source
     */
    @Select("SELECT * FROM ab_i18n_resource WHERE tenant_id = #{tenantId} AND source = #{source} AND deleted_flag = false")
    List<I18nResource> selectBySource(@Param("tenantId") Long tenantId, @Param("source") String source);

    /**
     * Find resources by reference (model/field/page derived keys)
     */
    @Select("SELECT * FROM ab_i18n_resource WHERE tenant_id = #{tenantId} AND ref_type = #{refType} AND ref_id = #{refId} AND deleted_flag = false")
    List<I18nResource> selectByRef(
        @Param("tenantId") Long tenantId,
        @Param("refType") String refType,
        @Param("refId") Long refId
    );

    // ==================== Batch Insert/Update ====================

    /**
     * Upsert a single resource (insert or update on conflict)
     */
    @Insert("""
        INSERT INTO ab_i18n_resource
        (pid, tenant_id, i18n_key, lang, value, source, ref_type, ref_id, status, created_at, updated_at, created_by, deleted_flag)
        VALUES
        (#{pid}, #{tenantId}, #{i18nKey}, #{lang}, #{value}, #{source}, #{refType}, #{refId}, #{status}, NOW(), NOW(), #{createdBy}, FALSE)
        ON CONFLICT (tenant_id, i18n_key, lang)
        DO UPDATE SET
            value = EXCLUDED.value,
            source = EXCLUDED.source,
            ref_type = EXCLUDED.ref_type,
            ref_id = EXCLUDED.ref_id,
            status = EXCLUDED.status,
            updated_at = NOW(),
            updated_by = EXCLUDED.created_by
        """)
    int upsert(I18nResource resource);

    /**
     * Batch insert (skip on conflict)
     */
    @Insert("""
        <script>
        INSERT INTO ab_i18n_resource
        (pid, tenant_id, i18n_key, lang, value, source, ref_type, ref_id, status, created_at, updated_at, created_by, deleted_flag)
        VALUES
        <foreach collection="list" item="item" separator=",">
        (#{item.pid}, #{item.tenantId}, #{item.i18nKey}, #{item.lang}, #{item.value}, #{item.source}, #{item.refType}, #{item.refId}, #{item.status}, NOW(), NOW(), #{item.createdBy}, FALSE)
        </foreach>
        ON CONFLICT (tenant_id, i18n_key, lang) DO NOTHING
        </script>
        """)
    int batchInsertIgnore(@Param("list") List<I18nResource> resources);

    // ==================== Pagination Query ====================

    /**
     * Paginated query with filters
     */
    @Select("""
        <script>
        SELECT * FROM ab_i18n_resource
        WHERE tenant_id = #{tenantId} AND deleted_flag = false
        <if test="lang != null and lang != ''">
            AND lang = #{lang}
        </if>
        <if test="source != null and source != ''">
            AND source = #{source}
        </if>
        <if test="status != null and status != ''">
            AND status = #{status}
        </if>
        <if test="keyPrefix != null and keyPrefix != ''">
            AND i18n_key LIKE CONCAT(#{keyPrefix}, '%')
        </if>
        <if test="keyword != null and keyword != ''">
            AND (i18n_key LIKE CONCAT('%', #{keyword}, '%') OR value LIKE CONCAT('%', #{keyword}, '%'))
        </if>
        ORDER BY i18n_key ASC
        </script>
        """)
    IPage<I18nResource> selectPageList(
        Page<?> page,
        @Param("tenantId") Long tenantId,
        @Param("lang") String lang,
        @Param("source") String source,
        @Param("status") String status,
        @Param("keyPrefix") String keyPrefix,
        @Param("keyword") String keyword
    );

    // ==================== Statistics ====================

    /**
     * Count by language
     */
    @Select("SELECT lang, COUNT(*) as count FROM ab_i18n_resource WHERE tenant_id = #{tenantId} AND deleted_flag = false GROUP BY lang")
    List<Map<String, Object>> countByLang(@Param("tenantId") Long tenantId);

    /**
     * Count by source
     */
    @Select("SELECT source, COUNT(*) as count FROM ab_i18n_resource WHERE tenant_id = #{tenantId} AND deleted_flag = false GROUP BY source")
    List<Map<String, Object>> countBySource(@Param("tenantId") Long tenantId);

    /**
     * Get all distinct languages
     */
    @Select("SELECT DISTINCT lang FROM ab_i18n_resource WHERE tenant_id = #{tenantId} AND deleted_flag = false ORDER BY lang")
    List<String> selectDistinctLangs(@Param("tenantId") Long tenantId);

    // ==================== Coverage Statistics ====================

    /**
     * Get i18n keys that exist in base locale but are missing in the target locale.
     * Returns up to {@code limit} rows.
     */
    @Select("""
        SELECT base.i18n_key
        FROM ab_i18n_resource base
        WHERE base.tenant_id = #{tenantId}
          AND base.lang = #{baseLang}
          AND base.deleted_flag = false
          AND NOT EXISTS (
              SELECT 1 FROM ab_i18n_resource target
              WHERE target.tenant_id = #{tenantId}
                AND target.lang = #{targetLang}
                AND target.i18n_key = base.i18n_key
                AND target.deleted_flag = false
          )
        ORDER BY base.i18n_key
        LIMIT #{limit}
        """)
    List<String> selectMissingKeys(
        @Param("tenantId") Long tenantId,
        @Param("baseLang") String baseLang,
        @Param("targetLang") String targetLang,
        @Param("limit") int limit
    );

    /**
     * Count how many base-locale keys are missing in the target locale.
     */
    @Select("""
        SELECT COUNT(*)
        FROM ab_i18n_resource base
        WHERE base.tenant_id = #{tenantId}
          AND base.lang = #{baseLang}
          AND base.deleted_flag = false
          AND NOT EXISTS (
              SELECT 1 FROM ab_i18n_resource target
              WHERE target.tenant_id = #{tenantId}
                AND target.lang = #{targetLang}
                AND target.i18n_key = base.i18n_key
                AND target.deleted_flag = false
          )
        """)
    long countMissingKeys(
        @Param("tenantId") Long tenantId,
        @Param("baseLang") String baseLang,
        @Param("targetLang") String targetLang
    );

    // ==================== Delete ====================

    /**
     * Delete by reference (when model/field is deleted)
     */
    @Update("UPDATE ab_i18n_resource SET deleted_flag = TRUE, updated_at = NOW() WHERE tenant_id = #{tenantId} AND ref_type = #{refType} AND ref_id = #{refId}")
    int deleteByRef(
        @Param("tenantId") Long tenantId,
        @Param("refType") String refType,
        @Param("refId") Long refId
    );

    /**
     * Delete by key prefix (when cleaning up a scope)
     */
    @Update("UPDATE ab_i18n_resource SET deleted_flag = TRUE, updated_at = NOW() WHERE tenant_id = #{tenantId} AND i18n_key LIKE CONCAT(#{prefix}, '%')")
    int deleteByKeyPrefix(@Param("tenantId") Long tenantId, @Param("prefix") String prefix);
}
