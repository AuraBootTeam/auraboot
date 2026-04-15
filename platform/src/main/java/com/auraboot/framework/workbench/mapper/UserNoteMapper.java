package com.auraboot.framework.workbench.mapper;

import com.auraboot.framework.workbench.entity.UserNote;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

/**
 * Mapper for UserNote entity.
 *
 * @since 6.5.0
 */
@Mapper
public interface UserNoteMapper extends BaseMapper<UserNote> {

    @Select("""
        SELECT * FROM ab_user_note
        WHERE user_id = #{userId} AND tenant_id = #{tenantId}
        LIMIT 1
        """)
    UserNote findByUser(@Param("userId") Long userId, @Param("tenantId") Long tenantId);
}
