package com.auraboot.framework.email.mapper;

import com.auraboot.framework.email.model.EmailSequenceEnrollment;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Mapper for {@link EmailSequenceEnrollment}.
 *
 * @since 6.5.0
 */
@Mapper
public interface EmailSequenceEnrollmentMapper extends BaseMapper<EmailSequenceEnrollment> {

    /**
     * Fetches all active enrollments whose {@code next_send_at} is due (in the past or now).
     * Called by the sequence executor job on a scheduled basis.
     */
    @Select("""
        SELECT * FROM ab_email_sequence_enrollment
        WHERE status      = 'active'
          AND next_send_at <= NOW()
        ORDER BY next_send_at ASC
        """)
    List<EmailSequenceEnrollment> findDueEnrollments();
}
