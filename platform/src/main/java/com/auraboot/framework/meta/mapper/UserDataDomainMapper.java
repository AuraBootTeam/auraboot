package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.entity.UserDataDomain;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * Mapper for UserDataDomain entity.
 *
 * @since 5.2.0
 */
@Mapper
public interface UserDataDomainMapper extends BaseMapper<UserDataDomain> {

    @Select("""
        SELECT * FROM ab_user_data_domain
        WHERE user_id = #{userId}
        ORDER BY is_primary DESC, created_at ASC
        """)
    List<UserDataDomain> findByUserId(@Param("userId") Long userId);

    @Select("""
        SELECT * FROM ab_user_data_domain
        WHERE domain_id = #{domainId}
        ORDER BY created_at ASC
        """)
    List<UserDataDomain> findByDomainId(@Param("domainId") Long domainId);

    @Select("""
        SELECT domain_id FROM ab_user_data_domain
        WHERE user_id = #{userId}
        """)
    List<Long> findDomainIdsByUserId(@Param("userId") Long userId);

    @Select("""
        SELECT * FROM ab_user_data_domain
        WHERE user_id = #{userId} AND domain_id = #{domainId}
        """)
    UserDataDomain findByUserAndDomain(@Param("userId") Long userId,
                                        @Param("domainId") Long domainId);

    @Delete("""
        DELETE FROM ab_user_data_domain
        WHERE user_id = #{userId} AND domain_id = #{domainId}
        """)
    int deleteByUserAndDomain(@Param("userId") Long userId,
                               @Param("domainId") Long domainId);

    @Select("""
        SELECT user_id FROM ab_user_data_domain
        WHERE domain_id = #{domainId}
        """)
    List<Long> findUserIdsByDomainId(@Param("domainId") Long domainId);
}
