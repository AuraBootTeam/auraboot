package com.auraboot.framework.email.mapper;

import com.auraboot.framework.email.model.EmailSequenceStep;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Mapper for {@link EmailSequenceStep}.
 *
 * @since 6.5.0
 */
@Mapper
public interface EmailSequenceStepMapper extends BaseMapper<EmailSequenceStep> {

    /**
     * Returns all steps for a sequence in execution order (ascending step_order).
     */
    @Select("""
        SELECT * FROM ab_email_sequence_step
        WHERE sequence_id = #{sequenceId}
        ORDER BY step_order ASC
        """)
    List<EmailSequenceStep> findBySequenceId(@Param("sequenceId") Long sequenceId);
}
