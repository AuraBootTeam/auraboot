package com.auraboot.framework.email.mapper;

import com.auraboot.framework.email.model.EmailSequence;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * Mapper for {@link EmailSequence}.
 *
 * <p>Standard CRUD is provided by {@link BaseMapper}. Filtering by status and tenant
 * is handled via MyBatis Plus {@code QueryWrapper} in the service layer.</p>
 *
 * @since 6.5.0
 */
@Mapper
public interface EmailSequenceMapper extends BaseMapper<EmailSequence> {
    // No custom queries — use QueryWrapper<EmailSequence> in service layer.
}
