package com.auraboot.framework.plugin.marketplace.entity;

import com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler;
import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import org.apache.ibatis.type.JdbcType;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "ab_marketplace_solution", autoResultMap = true)
public class MarketplaceSolution {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("pid")
    private String pid;

    @TableField("code")
    private String code;

    @TableField("name")
    private String name;

    @TableField("name_zh")
    private String nameZh;

    @TableField("name_en")
    private String nameEn;

    @TableField("description")
    private String description;

    @TableField("description_zh")
    private String descriptionZh;

    @TableField("description_en")
    private String descriptionEn;

    @TableField("industry")
    private String industry;

    @TableField(value = "plugin_codes", typeHandler = JsonbStringTypeHandler.class, jdbcType = JdbcType.OTHER)
    private String pluginCodes;

    @TableField(value = "config_template", typeHandler = JsonbStringTypeHandler.class, jdbcType = JdbcType.OTHER)
    private String configTemplate;

    @TableField(value = "sample_data_template", typeHandler = JsonbStringTypeHandler.class, jdbcType = JdbcType.OTHER)
    private String sampleDataTemplate;

    @TableField("icon_url")
    private String iconUrl;

    @TableField("cover_image_url")
    private String coverImageUrl;

    @TableField(value = "screenshots", typeHandler = JsonbStringTypeHandler.class, jdbcType = JdbcType.OTHER)
    private String screenshots;

    @TableField("readme_markdown")
    private String readmeMarkdown;

    @TableField("price_type")
    private String priceType;

    @TableField("price")
    private BigDecimal price;

    @TableField("status")
    private String status;

    @TableField("install_count")
    private Integer installCount;

    @TableField("average_rating")
    private BigDecimal averageRating;

    @TableField("review_count")
    private Integer reviewCount;

    @TableField("featured")
    private Boolean featured;

    @TableField(value = "tags", typeHandler = JsonbStringTypeHandler.class, jdbcType = JdbcType.OTHER)
    private String tags;

    @TableField("sort_order")
    private Integer sortOrder;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
    private Instant updatedAt;

    @TableField("published_at")
    private Instant publishedAt;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("deleted_flag")
    @TableLogic(value = "false", delval = "true")
    private Boolean deletedFlag;
}
