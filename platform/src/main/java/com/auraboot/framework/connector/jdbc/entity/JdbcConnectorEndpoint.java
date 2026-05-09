package com.auraboot.framework.connector.jdbc.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

@Data
@TableName("ab_jdbc_connector_endpoint")
public class JdbcConnectorEndpoint {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String connectorPid;
    private String code;
    private String name;
    /** "query" (SELECT) or "update" (INSERT/UPDATE/DELETE). */
    private String operation;
    private String sqlTemplate;
}
