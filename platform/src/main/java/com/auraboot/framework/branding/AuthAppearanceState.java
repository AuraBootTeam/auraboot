package com.auraboot.framework.branding;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.auraboot.framework.application.typehandler.JsonNodeTypeHandler;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.ibatis.type.JdbcType;
import lombok.Data;

@Data
@TableName(value = "ab_auth_appearance_state", autoResultMap = true)
public class AuthAppearanceState {
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;
    private Long version;
    @TableField("published_version")
    private Long publishedVersion;
    @TableField(value = "draft", typeHandler = JsonNodeTypeHandler.class, jdbcType = JdbcType.OTHER)
    private JsonNode draft;
    @TableField(value = "published", typeHandler = JsonNodeTypeHandler.class, jdbcType = JdbcType.OTHER)
    private JsonNode published;
}
