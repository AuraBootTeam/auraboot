package com.auraboot.framework.email.mapper;

import com.auraboot.framework.email.model.EmailAccountMember;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Mapper for {@link EmailAccountMember}.
 *
 * @since 6.5.0
 */
@Mapper
public interface EmailAccountMemberMapper extends BaseMapper<EmailAccountMember> {

    /**
     * Returns all members of a shared mailbox, ordered by assignment weight descending.
     */
    @Select("""
        SELECT * FROM ab_email_account_member
        WHERE account_id = #{accountId}
        ORDER BY assignment_weight DESC, id ASC
        """)
    List<EmailAccountMember> findByAccountId(@Param("accountId") Long accountId);
}
