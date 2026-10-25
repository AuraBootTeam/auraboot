package com.auraboot.framework.user;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.mapper.UserMapper;
import com.auraboot.framework.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserEmailLinkIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private UserService userService;

    @Autowired
    private UserMapper userMapper;

    @Test
    void normalizedEmailUniquenessIsEnforcedByPostgres() {
        String runId = Long.toString(System.nanoTime());
        User first = userService.signUp(
                "first-" + runId + "@example.test", "Test2026x!", "First", "first-" + runId);
        User second = userService.signUp(
                "second-" + runId + "@example.test", "Test2026x!", "Second", "second-" + runId);

        assertEquals(1, userMapper.bindVerifiedEmail(first.getId(), "linked-" + runId + "@qq.com"));
        assertTrue(Boolean.TRUE.equals(userMapper.selectById(first.getId()).getEmailVerified()));
        assertThrows(DuplicateKeyException.class, () ->
                userMapper.bindVerifiedEmail(second.getId(), "LINKED-" + runId + "@QQ.COM"));
    }
}
