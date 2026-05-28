package com.auraboot.framework.semantic.mapper;

import com.auraboot.framework.semantic.entity.AbSemanticLineageEdge;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AbSemanticLineageEdgeMapper extends BaseMapper<AbSemanticLineageEdge> {

    /** All edges pointing OUT of a node (what this node depends on). */
    @Select("SELECT * FROM ab_semantic_lineage_edge "
          + "WHERE tenant_id = #{tenantId} AND src_node_pid = #{nodePid} "
          + "AND deleted_flag = FALSE")
    List<AbSemanticLineageEdge> findOutgoing(@Param("tenantId") Long tenantId,
                                              @Param("nodePid") String nodePid);

    /** All edges pointing IN to a node (what depends on this node — downstream impact). */
    @Select("SELECT * FROM ab_semantic_lineage_edge "
          + "WHERE tenant_id = #{tenantId} AND dst_node_pid = #{nodePid} "
          + "AND deleted_flag = FALSE")
    List<AbSemanticLineageEdge> findIncoming(@Param("tenantId") Long tenantId,
                                              @Param("nodePid") String nodePid);

    /** Hard-delete all edges originating from one node (used during YAML re-import). */
    @Delete("UPDATE ab_semantic_lineage_edge SET deleted_flag = TRUE "
          + "WHERE tenant_id = #{tenantId} AND src_node_pid = #{nodePid}")
    int softDeleteAllFrom(@Param("tenantId") Long tenantId, @Param("nodePid") String nodePid);
}
