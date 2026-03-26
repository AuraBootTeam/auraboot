package com.auraboot.framework.bpm.mapper;

import com.auraboot.framework.bpm.entity.BpmSignatureRecord;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * Mapper for BPM signature records.
 *
 * Note: sign_position is a JSONB column requiring autoResultMap for proper type handler resolution.
 * Use default methods with selectList() instead of @Select.
 */
@Mapper
public interface BpmSignatureRecordMapper extends BaseMapper<BpmSignatureRecord> {

    default BpmSignatureRecord findByPid(String pid) {
        return selectOne(new QueryWrapper<BpmSignatureRecord>()
                .eq("pid", pid));
    }

    default List<BpmSignatureRecord> findByProcessInstance(String processInstanceId) {
        return selectList(new QueryWrapper<BpmSignatureRecord>()
                .eq("process_instance_id", processInstanceId)
                .orderByDesc("signed_at"));
    }

    default List<BpmSignatureRecord> findByDocument(String documentId) {
        return selectList(new QueryWrapper<BpmSignatureRecord>()
                .eq("document_id", documentId)
                .orderByDesc("signed_at"));
    }
}
