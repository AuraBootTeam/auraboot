//package com.auraboot.framework.rbac.service;
//
//import com.auraboot.framework.application.TestApplication;
//import com.auraboot.framework.rbac.service.impl.PermissionServiceImpl;
//import org.junit.jupiter.api.Test;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.boot.test.context.SpringBootTest;
//import org.springframework.test.context.ActiveProfiles;
//
//@SpringBootTest(classes = TestApplication.class)
//@ActiveProfiles("test")
//public class PermissionServiceTest {
//
//    @Autowired
//    private PermissionServiceImpl permissionService;
//
//    @Test
//    public void testIsPermissionInitialized() {
//        boolean result = permissionService.isPermissionInitialized();
//        System.out.println("Permission initialized: " + result);
//    }
//}