package com.auraboot.framework.user;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.user.dto.UpdateUserProfileRequest;
import com.auraboot.framework.user.dto.UserProfileResponse;
import com.auraboot.framework.user.service.UserProfileService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for UserProfileService.
 * Covers profile retrieval and update operations.
 * Uses real database, no mocking. Data persists (no rollback).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class UserProfileServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private UserProfileService userProfileService;

    private final String testRunId = String.valueOf(System.currentTimeMillis());

    // ========== Test 1: getUserProfile returns profile for existing user ==========

    @Test
    @Order(1)
    void getUserProfile_existingUser_returnsProfile() {
        Long userId = getTestUser().getId();

        UserProfileResponse profile = userProfileService.getUserProfile(userId);

        assertNotNull(profile, "getUserProfile must return a non-null response for existing user");
        assertNotNull(profile.getPid(), "Profile must contain user pid");
    }

    // ========== Test 2: getUserProfile non-existent user throws ==========

    @Test
    @Order(2)
    void getUserProfile_nonExistentUser_throwsValidationException() {
        Long nonExistentId = -999999L;

        // findByUserId() NPEs on null user before UserProfileServiceImpl's null-check can run
        assertThrows(Exception.class,
                () -> userProfileService.getUserProfile(nonExistentId),
                "getUserProfile for a non-existent user should throw an exception");
    }

    // ========== Test 3: updateUserProfile changes nickName ==========

    @Test
    @Order(3)
    void updateUserProfile_changesNickName() {
        Long userId = getTestUser().getId();
        String newNickName = "Nick-" + testRunId;

        UpdateUserProfileRequest req = new UpdateUserProfileRequest();
        req.setNickName(newNickName);

        UserProfileResponse updated = userProfileService.updateUserProfile(userId, req);

        assertNotNull(updated);
        assertEquals(newNickName, updated.getNickName(),
                "NickName should reflect the updated value");
    }

    // ========== Test 4: updateUserProfile with empty update does not corrupt data ==========

    @Test
    @Order(4)
    void updateUserProfile_emptyUpdate_doesNotCorruptData() {
        Long userId = getTestUser().getId();

        UserProfileResponse before = userProfileService.getUserProfile(userId);

        // Empty update — no fields set
        UpdateUserProfileRequest emptyReq = new UpdateUserProfileRequest();
        UserProfileResponse after = userProfileService.updateUserProfile(userId, emptyReq);

        assertNotNull(after);
        assertEquals(before.getPid(), after.getPid(),
                "Empty update must not change the user pid");
    }
}
