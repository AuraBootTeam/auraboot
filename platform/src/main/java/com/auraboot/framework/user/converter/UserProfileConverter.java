package com.auraboot.framework.user.converter;

import com.auraboot.framework.common.converter.UtcDateTimeMapper;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.dto.UpdateUserProfileRequest;
import com.auraboot.framework.user.dto.UserProfileResponse;
import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;
import org.mapstruct.ReportingPolicy;

/**
 * 用户个人资料映射器
 */
@Mapper(
    componentModel = "spring",
    unmappedTargetPolicy = ReportingPolicy.IGNORE,
    nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE,
    uses = UtcDateTimeMapper.class
)
public interface UserProfileConverter {
    
    /**
     * 将User实体转换为UserProfileResponse
     * 
     * @param user 用户实体
     * @return 用户个人资料响应DTO
     */
    UserProfileResponse toUserProfileResponse(User user);
    
    /**
     * 将UpdateUserProfileRequest的数据更新到User实体
     * 只更新非空字段
     * 
     * @param user 目标用户实体
     * @param request 更新请求DTO
     */
    void updateUserFromRequest(@MappingTarget User user, UpdateUserProfileRequest request);
}