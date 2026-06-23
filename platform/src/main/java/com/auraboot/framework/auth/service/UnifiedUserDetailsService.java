package com.auraboot.framework.auth.service;

import com.auraboot.framework.auth.dto.CustomUserDetails;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.UnSupportedException;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * ### 为什么 Spring Security 这样设计？
 * 1. 历史遗留 ：Spring Security 最初设计时（2003年），主要使用用户名登录
 * 2. 向后兼容 ：修改接口会破坏大量现有代码
 * 3. 通用性考虑 ： username 被当作一个通用的用户标识符概念
 * ### Spring Security 的文档说明
 * 官方文档中明确说明：
 * <p>
 * The username parameter does not actually have to be a username. It can be any user identifier that is unique in your system.
 */
@Service
public class UnifiedUserDetailsService implements UserDetailsService {

    @Autowired
    private UserService userService;

    @Override
    public UserDetails loadUserByUsername(String identifier) throws UsernameNotFoundException {
        User user = findUserByIdentifier(identifier);

        if (user == null) {
            //tune 如何处理更优雅
            throw new UsernameNotFoundException("Invalid identifier: " + identifier);
        }

        return new CustomUserDetails(
                identifier,        // 保持登录时使用的标识符
                user.getPassword(),
                user.getId(),
                user.getPid(),     // 这是关键的稳定标识符
                null,
                user.isAccountNonExpired(),
                user.isAccountNonLocked(),
                user.isCredentialsNonExpired(),
                user.isEnabled()
        );
    }

    private User findUserByIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return null;
        }
        String normalized = identifier.trim();

        User byEmail = userService.findByEmail(normalized);
        if (byEmail != null) {
            return byEmail;
        }

        if (normalized.matches("\\d{11}")) {
            throw new UnSupportedException(ResponseCode.SystemError);
//            return userService.findByPhone(identifier);
        }

        User byUserName = userService.findByUserName(normalized);
        if (byUserName != null) {
            return byUserName;
        }

        return userService.findByPid(normalized);
    }

    public UserDetails loadUserById(Long userId) throws UsernameNotFoundException {
        User user = userService.findByUserId(userId);
        if (user == null) {
            throw new UsernameNotFoundException("Invalid userId: " + userId);
        }
        return new CustomUserDetails(
                user.getPid(),     // 使用pid作为username
                user.getPassword(),
                user.getId(),
                user.getPid(),
                null,
                user.isAccountNonExpired(),
                user.isAccountNonLocked(),
                user.isCredentialsNonExpired(),
                user.isEnabled()
        );
    }

}
