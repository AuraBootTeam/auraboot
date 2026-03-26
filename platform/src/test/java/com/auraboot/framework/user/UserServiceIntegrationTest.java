package com.auraboot.framework.user;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for UserService.
 * Covers sign-up, sign-in, and user lookup operations.
 * Uses real database, no mocking. Data persists (no rollback).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class UserServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private UserService userService;

    private final String testRunId = String.valueOf(System.currentTimeMillis());
    private final String testEmail = "test-" + testRunId + "@integration.test";
    private String createdUserPid;

    // ========== Test 1: signUp creates user ==========

    @Test
    @Order(1)
    void signUp_withValidData_createsUserAndReturnsUser() {
        User user = userService.signUp(testEmail, "Test2026x!", "Test User " + testRunId);

        assertNotNull(user, "signUp should return a non-null User");
        assertNotNull(user.getId(), "Created user must have an auto-generated id");
        assertNotNull(user.getPid(), "Created user must have a pid");
        assertEquals(testEmail, user.getEmail(), "Email must match");
        createdUserPid = user.getPid();
    }

    // ========== Test 2: signUp duplicate email throws ==========

    @Test
    @Order(2)
    void signUp_withDuplicateEmail_throwsException() {
        // testEmail was registered in test 1
        assertThrows(Exception.class,
                () -> userService.signUp(testEmail, "AnotherPass2026!", "Duplicate"),
                "Registering with a duplicate email must throw an exception");
    }

    // ========== Test 3: signIn correct password ==========

    @Test
    @Order(3)
    void signIn_withCorrectPassword_returnsUser() {
        User user = userService.signIn(testEmail, "Test2026x!");

        assertNotNull(user, "signIn with correct credentials must return User");
        assertEquals(testEmail, user.getEmail());
    }

    // ========== Test 4: signIn wrong password throws ==========

    @Test
    @Order(4)
    void signIn_withWrongPassword_throwsException() {
        assertThrows(Exception.class,
                () -> userService.signIn(testEmail, "WrongPassword!"),
                "signIn with wrong password must throw an exception");
    }

    // ========== Test 5: findByUserId ==========

    @Test
    @Order(5)
    void findByUserId_existingUser_returnsUser() {
        User byEmail = userService.findByEmail(testEmail);
        assertNotNull(byEmail, "findByEmail must return the registered user");

        User found = userService.findByUserId(byEmail.getId());
        assertNotNull(found, "findByUserId should find the user created in test 1");
        assertEquals(testEmail, found.getEmail());
    }

    // ========== Test 6: findByEmail ==========

    @Test
    @Order(6)
    void findByEmail_existingUser_returnsUser() {
        User user = userService.findByEmail(testEmail);

        assertNotNull(user, "findByEmail should find the registered user");
        assertEquals(testEmail, user.getEmail());
    }

    // ========== Test 7: findByPid ==========

    @Test
    @Order(7)
    void findByPid_existingUser_returnsUser() {
        assertNotNull(createdUserPid, "createdUserPid must be set by test 1");

        User user = userService.findByPid(createdUserPid);
        assertNotNull(user, "findByPid should find the user");
        assertEquals(testEmail, user.getEmail());
    }

    // ========== Test 8: update ==========

    @Test
    @Order(8)
    void update_changesNickName() {
        User user = userService.findByEmail(testEmail);
        assertNotNull(user);

        String newNickName = "Updated-" + testRunId;
        user.setNickName(newNickName);
        userService.update(user);

        User refreshed = userService.findByUserId(user.getId());
        assertEquals(newNickName, refreshed.getNickName(),
                "NickName should be updated in the database");
    }
}
