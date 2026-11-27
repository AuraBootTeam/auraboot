package com.auraboot.framework.plugin.mapper;

import com.auraboot.framework.plugin.handover.*;

import com.auraboot.framework.application.typehandler.JsonNodeTypeHandler;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import org.apache.ibatis.annotations.*;
import java.util.List;

/** Fixed read-only queries; table/column identifiers are never supplied by a request or contract. */
@Mapper
public interface HandoverInventoryMapper {
    @Data class Row { private JsonNode state; }
    @Select("SELECT to_jsonb(p) - 'settings' - 'config_schema' AS state FROM ab_plugin p WHERE tenant_id=#{tenant} AND pid=#{pid} AND deleted_flag IS NOT TRUE")
    @Results(id="snapshot", value=@Result(column="state", property="state", typeHandler=JsonNodeTypeHandler.class))
    List<Row> plugin(@Param("tenant") Long tenant, @Param("pid") String pid);
    @Select("SELECT to_jsonb(p) - 'settings' - 'config_schema' AS state FROM ab_plugin p WHERE tenant_id=#{tenant} AND plugin_id=#{code} AND deleted_flag IS NOT TRUE")
    @ResultMap("snapshot") List<Row> pluginByCode(@Param("tenant") Long tenant, @Param("code") String code);
    @Select("SELECT to_jsonb(r) AS state FROM ab_plugin_resource r WHERE tenant_id=#{tenant} AND plugin_pid=#{pid} ORDER BY resource_type,resource_code,id")
    @ResultMap("snapshot") List<Row> tracking(@Param("tenant") Long tenant,@Param("pid") String pid);
    @Select("SELECT to_jsonb(f) AS state FROM ab_meta_field f WHERE tenant_id=#{tenant} AND pid=#{pid} AND deleted_flag IS NOT TRUE")
    @ResultMap("snapshot") List<Row> field(@Param("tenant") Long tenant,@Param("pid") String pid);
    @Select("SELECT to_jsonb(d) AS state FROM ab_dict d WHERE tenant_id=#{tenant} AND pid=#{pid} AND deleted_flag IS NOT TRUE")
    @ResultMap("snapshot") List<Row> dict(@Param("tenant") Long tenant,@Param("pid") String pid);
    @Select("SELECT to_jsonb(d) AS state FROM ab_dict d WHERE tenant_id=#{tenant} AND code=#{code} AND deleted_flag IS NOT TRUE")
    @ResultMap("snapshot") List<Row> dictByCode(@Param("tenant") Long tenant,@Param("code") String code);
    @Select("SELECT to_jsonb(i) AS state FROM ab_dict_item i WHERE tenant_id=#{tenant} AND dict_id=#{dict} ORDER BY id")
    @ResultMap("snapshot") List<Row> dictItems(@Param("tenant") Long tenant,@Param("dict") Long dict);
    @Select("SELECT to_jsonb(b) AS state FROM ab_meta_model_field_binding b WHERE tenant_id=#{tenant} AND pid=#{pid} AND deleted_flag IS NOT TRUE")
    @ResultMap("snapshot") List<Row> binding(@Param("tenant") Long tenant,@Param("pid") String pid);
    @Select("SELECT to_jsonb(m) AS state FROM ab_meta_model m WHERE tenant_id=#{tenant} AND id=#{id} AND deleted_flag IS NOT TRUE")
    @ResultMap("snapshot") List<Row> model(@Param("tenant") Long tenant,@Param("id") Long id);
    @Select("SELECT to_jsonb(f) AS state FROM ab_meta_field f WHERE tenant_id=#{tenant} AND id=#{id} AND deleted_flag IS NOT TRUE")
    @ResultMap("snapshot") List<Row> fieldById(@Param("tenant") Long tenant,@Param("id") Long id);
    @Select("SELECT to_jsonb(b) AS state FROM ab_meta_field_dict_binding b WHERE tenant_id=#{tenant} AND field_id=#{id} AND deleted_flag IS NOT TRUE ORDER BY id")
    @ResultMap("snapshot") List<Row> fieldDicts(@Param("tenant") Long tenant,@Param("id") Long id);
    @Select("SELECT to_jsonb(f) AS state FROM ab_meta_field f WHERE tenant_id=#{tenant} AND plugin_pid=#{pid} AND deleted_flag IS NOT TRUE ORDER BY id")
    @ResultMap("snapshot") List<Row> ownedFields(@Param("tenant") Long tenant,@Param("pid") String pid);
    @Select("SELECT to_jsonb(d) AS state FROM ab_dict d WHERE tenant_id=#{tenant} AND plugin_pid=#{pid} AND deleted_flag IS NOT TRUE ORDER BY id")
    @ResultMap("snapshot") List<Row> ownedDicts(@Param("tenant") Long tenant,@Param("pid") String pid);
    @Select("SELECT to_jsonb(r) AS state FROM ab_plugin_resource r WHERE tenant_id=#{tenant} AND resource_type=#{type} AND resource_code=#{code} ORDER BY id")
    @ResultMap("snapshot") List<Row> trackingByCode(@Param("tenant") Long tenant,@Param("type") String type,@Param("code") String code);
}
