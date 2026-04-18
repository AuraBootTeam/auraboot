package com.auraboot.framework.user.service.impl;

import com.auraboot.framework.auth.service.PasswordManagementService;
import com.auraboot.framework.auth.service.PasswordPolicyService;
import com.auraboot.framework.common.constant.ResponseCode;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.event.user.UserRegisteredEvent;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.dto.UserSearchDTO;
import com.auraboot.framework.user.mapper.UserMapper;
import com.auraboot.framework.user.exception.UserException;
import com.auraboot.framework.user.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;


/**
 * @author 高海军 帝奇 Apr 9, 2015 9:30:37 PM
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final ApplicationEventPublisher applicationEventPublisher;
    private final PasswordEncoder passwordEncoder;
    private final UserMapper userMapper;
    private final PasswordManagementService passwordManagementService;
    private final PasswordPolicyService passwordPolicyService;

    @Override
    public List<UserSearchDTO> searchInTenant(Long tenantId, String keyword, int size) {
        Objects.requireNonNull(tenantId, "tenantId is required for user search");
        int clamped = Math.max(1, Math.min(size, 200));
        String normalized = keyword == null ? "" : keyword.trim();
        String likePattern = "%" + normalized.toLowerCase() + "%";
        // Picker search does not exclude any user — users should be able to pick themselves
        // (e.g. "assign task to me"). The controller layer may pass an explicit exclude if needed.
        List<Map<String, Object>> rows = userMapper.searchUsersByTenant(tenantId, null, likePattern, clamped);
        return rows.stream()
                .map(row -> UserSearchDTO.builder()
                        .pid(stringValue(row.get("pid")))
                        .displayName(stringValue(row.get("display_name")))
                        .email(stringValue(row.get("email")))
                        .avatarUrl(stringValue(row.get("avatar_url")))
                        .departmentName(stringValue(row.get("department_name")))
                        .build())
                .toList();
    }

    @Override
    public UserSearchDTO findInTenantByPid(Long tenantId, String pid) {
        Objects.requireNonNull(tenantId, "tenantId is required");
        if (pid == null || pid.isBlank()) {
            return null;
        }
        Map<String, Object> row = userMapper.findUserInTenantByPid(tenantId, pid);
        if (row == null) {
            return null;
        }
        return UserSearchDTO.builder()
                .pid(stringValue(row.get("pid")))
                .displayName(stringValue(row.get("display_name")))
                .email(stringValue(row.get("email")))
                .avatarUrl(stringValue(row.get("avatar_url")))
                .departmentName(stringValue(row.get("department_name")))
                .build();
    }

    private static String stringValue(Object v) {
        return v == null ? null : v.toString();
    }

    @Override
    public List<User> findByUserIds(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<User> users = userMapper.selectBatchIds(userIds);
        // Clear passwords before returning
        if (users != null) {
            users.forEach(u -> u.setPassword(null));
        }
        return users != null ? users : Collections.emptyList();
    }

    @Override
    public User findByPid(String pid) {
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.lambda().eq(User::getPid, pid);
        return userMapper.selectOne(queryWrapper);
    }
    
    @Override
    public User signUp(String email, String rawPassword, String displayName) throws UserException {
        User userByEmail = queryUserByEmail(email);

        if (null == userByEmail ) {
            // Validate password against policy before encoding
            List<String> policyErrors = passwordPolicyService.validate(rawPassword.trim());
            if (!policyErrors.isEmpty()) {
                throw new BusinessException("Password does not meet policy: " + String.join("; ", policyErrors));
            }

            String encodedPassword = passwordEncoder.encode(rawPassword.trim());

            User signUpUser = new User();
            signUpUser.setPid(UniqueIdGenerator.generate());
            signUpUser.setEmail(email);
            String resolvedName = (displayName != null && !displayName.isBlank()) ? displayName
                    : (email.contains("@") ? email.substring(0, email.indexOf('@')) : email);
            signUpUser.setNickName(resolvedName);
            signUpUser.setPassword(encodedPassword);
            signUpUser.setEnabled(true);
            signUpUser.setAccountNonExpired(true);
            signUpUser.setAccountNonLocked(true);
            signUpUser.setCredentialsNonExpired(true);
            userMapper.insert(signUpUser);

            User user = findByUserId(signUpUser.getId());

            publishEvent(user);

            return user;
        } else {
            throw new UserException(ResponseCode.IdentifierAlreadyBeenTaken);
        }
    }

    private User queryUserByName(String name) {
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.lambda().eq(User::getUserName, name);
        User user = userMapper.selectOne(queryWrapper);
        return user;
    }

    private User queryUserByEmail(String email) {
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.lambda().eq(User::getEmail, email);
        User user = userMapper.selectOne(queryWrapper);
        return user;
    }



    @Override
    public User signIn(String email, String rawPassword) throws UserException {
        User user = this.queryUserByEmail(email);

        if (null == user) {
            throw new UserException(ResponseCode.InvalidUserNameOrPassword);
        }

        // Check account lockout before attempting password match
        if (passwordManagementService.isAccountLocked(user)) {
            throw new BusinessException("Account is locked due to too many failed login attempts");
        }

        String encodedPassword = user.getPassword();

        if (passwordEncoder.matches(rawPassword, encodedPassword)) {
            // Reset failed attempts on successful login
            passwordManagementService.resetLoginFailures(user);

            user.setLastSignInAt(user.getCurrentSignInAt());
            user.setCurrentSignInAt(Instant.now());
            user.setSignInCount(user.getSignInCount() + 1);
            userMapper.updateById(user);

            return user;
        } else {
            // Record failed login attempt (may lock account)
            passwordManagementService.recordLoginFailure(user);
            throw new UserException(ResponseCode.InvalidUserNameOrPassword);
        }

    }


    @Override
    public void signOut(Long userId)  {
        // Increment security version to invalidate all existing JWT tokens
        passwordManagementService.incrementSecurityVersion(userId);
        log.info("User signed out, security version incremented: userId={}", userId);
    }

    @Override
    public User findByUserId(Long userId) {
        User user = this.findUserByUserFromDB(userId);
        if (user == null) {
            return null;
        }
        //reset
        user.setPassword(null);
        return user;
    }

    @Override
    public User findByUserName(String userName) {
        return this.queryUserByName(userName);
    }

    @Override
    public User findByEmail(String email) {
        return this.queryUserByEmail(email);
    }


    @Override
    public void update(User user) {
        userMapper.updateById(user);
    }



    private User findUserByUserFromDB(Long userId) {
        return userMapper.selectById(userId);
    }


    private void publishEvent(User user) {

        // Publish user registered event (Spring accepts any Object)
        UserRegisteredEvent event = new UserRegisteredEvent(user.getId());

        applicationEventPublisher.publishEvent(event);

        log.info("User registered successfully: {}", user.getId());
    }
}
