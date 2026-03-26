package com.auraboot.framework.auth.mapper;

import com.auraboot.framework.auth.entity.PasswordHistory;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface PasswordHistoryMapper extends BaseMapper<PasswordHistory> {

    /**
     * Find recent password hashes for a user, ordered newest first.
     */
    @Select("SELECT password_hash FROM ab_password_history WHERE user_id = #{userId} ORDER BY created_at DESC LIMIT #{limit}")
    List<String> findRecentHashes(@Param("userId") Long userId, @Param("limit") int limit);
}
