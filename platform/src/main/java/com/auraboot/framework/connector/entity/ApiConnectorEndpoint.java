package com.auraboot.framework.connector.entity;

import com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

/**
 * API connector endpoint definition entity.
 *
 * @since 5.1.0
 */
@Data
@TableName(value = "ab_api_connector_endpoint", autoResultMap = true)
public class ApiConnectorEndpoint {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("connector_pid")
    private String connectorPid;

    @TableField("code")
    private String code;

    @TableField("name")
    private String name;

    /**
     * HTTP method: GET / POST / PUT / DELETE.
     */
    @TableField("method")
    private String method;

    @TableField("path")
    private String path;

    @TableField(value = "request_schema", typeHandler = JsonbStringTypeHandler.class)
    private String requestSchema;

    @TableField(value = "response_mapping", typeHandler = JsonbStringTypeHandler.class)
    private String responseMapping;
}
