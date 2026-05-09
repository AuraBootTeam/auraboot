package com.auraboot.framework.organization.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.organization.dto.CreateEmployeeRequest;
import com.auraboot.framework.organization.dto.LinkMemberRequest;
import com.auraboot.framework.organization.dto.OrgEmployeeDTO;
import com.auraboot.framework.organization.dto.TransferRequest;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrgEmployeeServiceImpl")
class OrgEmployeeServiceImplTest {

    @Mock private DynamicDataService dynamicDataService;
    @Mock private UserService userService;
    @Mock private TenantMemberService tenantMemberService;
    @Mock private OrganizationServiceImpl organizationService;
    @Mock private JdbcTemplate jdbcTemplate;

    private OrgEmployeeServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        service = new OrgEmployeeServiceImpl(
            dynamicDataService, userService, tenantMemberService, organizationService);
        injectField(service, "jdbcTemplate", jdbcTemplate);
        MetaContext.setContext(1L, 5L, "user-pid", "alice");
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    static void injectField(Object target, String name, Object value) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private CreateEmployeeRequest createRequest() {
        CreateEmployeeRequest r = new CreateEmployeeRequest();
        r.setName("Alice");
        r.setEmail("alice@x");
        r.setPhone("123");
        r.setGender("F");
        r.setDeptPid("d1");
        r.setPositionPid("p1");
        r.setManagerPid("m1");
        return r;
    }

    @Test
    @DisplayName("createWithUser creates user, member, employee, and writes back link")
    void createWithUserHappy() {
        User user = new User();
        user.setId(99L);
        user.setPid("user-pid-99");
        when(userService.signUp(eq("alice@x"), anyString(), eq("Alice"))).thenReturn(user);

        TenantMember member = new TenantMember();
        member.setId(50L);
        member.setPid("mem-50");
        when(tenantMemberService.addMember(99L, 1L, "ACTIVE")).thenReturn(member);

        Map<String, Object> created = new HashMap<>();
        created.put("id", 200L);
        created.put("pid", "emp-pid");
        when(dynamicDataService.create(eq("org_employee"), anyMap())).thenReturn(created);

        OrgEmployeeDTO dto = new OrgEmployeeDTO("emp-pid", "Alice", null, "alice@x", "123",
            "F", "d1", "Eng", "p1", "Engineer", "ACTIVE", "human", "mem-50", "user-pid-99");
        when(organizationService.toEmployeeDTO(created)).thenReturn(dto);

        OrgEmployeeDTO result = service.createWithUser(createRequest());
        assertEquals("emp-pid", result.pid());
        assertEquals(200L, member.getEmployeeId());
        verify(tenantMemberService).updateMember(member);
    }

    @Test
    @DisplayName("createWithUser handles minimal request without optional fields")
    void createWithUserMinimal() {
        CreateEmployeeRequest r = new CreateEmployeeRequest();
        r.setName("B");
        r.setEmail("b@x");
        r.setPhone("9");
        r.setDeptPid("d1");

        User user = new User();
        user.setId(1L);
        user.setPid("u1");
        when(userService.signUp(anyString(), anyString(), anyString())).thenReturn(user);
        TenantMember m = new TenantMember();
        m.setId(2L);
        m.setPid("m1");
        when(tenantMemberService.addMember(any(), any(), any())).thenReturn(m);

        Map<String, Object> created = new HashMap<>();
        created.put("id", 5);
        created.put("pid", "emp1");
        when(dynamicDataService.create(eq("org_employee"), anyMap())).thenReturn(created);
        when(organizationService.toEmployeeDTO(created)).thenReturn(null);

        service.createWithUser(r);
        assertEquals(5L, m.getEmployeeId());
    }

    @Test
    @DisplayName("linkMember succeeds and writes back")
    void linkMemberHappy() {
        TenantMember member = new TenantMember();
        member.setId(50L);
        member.setPid("mem-50");
        member.setUserId(99L);
        when(tenantMemberService.findByPid("mem-50")).thenReturn(member);

        User user = new User();
        user.setNickName("Alice");
        user.setEmail("alice@x");
        user.setPid("u-pid");
        when(userService.findByUserId(99L)).thenReturn(user);

        Map<String, Object> created = new HashMap<>();
        created.put("id", 200L);
        created.put("pid", "emp-pid");
        when(dynamicDataService.create(eq("org_employee"), anyMap())).thenReturn(created);
        when(organizationService.toEmployeeDTO(created)).thenReturn(null);

        LinkMemberRequest req = new LinkMemberRequest();
        req.setMemberPid("mem-50");
        req.setDeptPid("d1");
        req.setPositionPid("p1");

        service.linkMember(req);
        assertEquals(200L, member.getEmployeeId());
        verify(tenantMemberService).updateMember(member);
    }

    @Test
    @DisplayName("linkMember uses userName when nickName null")
    void linkMemberUsesUserName() {
        TenantMember m = new TenantMember();
        m.setId(50L);
        m.setUserId(99L);
        when(tenantMemberService.findByPid("mem-50")).thenReturn(m);

        User u = new User();
        u.setUserName("bob");
        u.setEmail("b@x");
        u.setPid("u");
        when(userService.findByUserId(99L)).thenReturn(u);

        Map<String, Object> created = new HashMap<>();
        created.put("id", 1L);
        when(dynamicDataService.create(eq("org_employee"), anyMap())).thenReturn(created);

        LinkMemberRequest req = new LinkMemberRequest();
        req.setMemberPid("mem-50");
        req.setDeptPid("d1");
        service.linkMember(req);
    }

    @Test
    @DisplayName("linkMember fails when member missing / already linked / user missing")
    void linkMemberRejections() {
        LinkMemberRequest req = new LinkMemberRequest();
        req.setMemberPid("mem-50");
        req.setDeptPid("d1");

        when(tenantMemberService.findByPid("mem-50")).thenReturn(null);
        assertThrows(BusinessException.class, () -> service.linkMember(req));

        TenantMember alreadyLinked = new TenantMember();
        alreadyLinked.setEmployeeId(123L);
        when(tenantMemberService.findByPid("mem-50")).thenReturn(alreadyLinked);
        assertThrows(BusinessException.class, () -> service.linkMember(req));

        TenantMember m = new TenantMember();
        m.setUserId(99L);
        when(tenantMemberService.findByPid("mem-50")).thenReturn(m);
        when(userService.findByUserId(99L)).thenReturn(null);
        assertThrows(BusinessException.class, () -> service.linkMember(req));
    }

    @Test
    @DisplayName("unlinkMember clears both sides via SQL")
    void unlinkMember() {
        Map<String, Object> emp = new HashMap<>();
        emp.put("org_emp_member_id", "mem-50");
        when(dynamicDataService.getById("org_employee", "emp-1")).thenReturn(emp);

        service.unlinkMember("emp-1");
        verify(jdbcTemplate).update(contains("mt_org_employee"), eq("emp-1"));
        verify(jdbcTemplate).update(contains("ab_tenant_member"), eq("mem-50"));
    }

    @Test
    @DisplayName("unlinkMember skips member-side update when no member")
    void unlinkMemberNoMember() {
        Map<String, Object> emp = new HashMap<>();
        when(dynamicDataService.getById("org_employee", "emp-1")).thenReturn(emp);
        service.unlinkMember("emp-1");
        verify(jdbcTemplate, times(1)).update(contains("mt_org_employee"), eq("emp-1"));
        verify(jdbcTemplate, never()).update(contains("ab_tenant_member"), anyString());
    }

    @Test
    @DisplayName("unlinkMember fails when employee missing")
    void unlinkMemberMissing() {
        when(dynamicDataService.getById("org_employee", "missing")).thenReturn(null);
        assertThrows(BusinessException.class, () -> service.unlinkMember("missing"));
    }

    @Test
    @DisplayName("transfer updates dept and optional position")
    void transfer() {
        Map<String, Object> emp = new HashMap<>();
        when(dynamicDataService.getById("org_employee", "emp-1")).thenReturn(emp);

        TransferRequest req = new TransferRequest();
        req.setNewDeptPid("d2");
        req.setNewPositionPid("p2");
        service.transfer("emp-1", req);
        verify(dynamicDataService).update(eq("org_employee"), eq("emp-1"), anyMap());
    }

    @Test
    @DisplayName("transfer without position pid still updates")
    void transferNoPosition() {
        when(dynamicDataService.getById("org_employee", "emp-1")).thenReturn(new HashMap<>());
        TransferRequest req = new TransferRequest();
        req.setNewDeptPid("d2");
        service.transfer("emp-1", req);
        verify(dynamicDataService).update(eq("org_employee"), eq("emp-1"), anyMap());
    }

    @Test
    @DisplayName("transfer fails when employee missing")
    void transferMissing() {
        when(dynamicDataService.getById("org_employee", "missing")).thenReturn(null);
        TransferRequest req = new TransferRequest();
        req.setNewDeptPid("d2");
        assertThrows(BusinessException.class, () -> service.transfer("missing", req));
    }

    @Test
    @DisplayName("batchTransfer iterates pids")
    void batchTransfer() {
        when(dynamicDataService.getById(eq("org_employee"), anyString())).thenReturn(new HashMap<>());
        TransferRequest req = new TransferRequest();
        req.setNewDeptPid("d2");
        service.batchTransfer(List.of("e1", "e2", "e3"), req);
        verify(dynamicDataService, times(3)).update(eq("org_employee"), anyString(), anyMap());
    }

    @Test
    @DisplayName("createWithUser handles String id and Number id and rejects unparseable")
    void createWithUserIdExtraction() {
        // String id
        User user = new User();
        user.setId(1L);
        user.setPid("u");
        when(userService.signUp(anyString(), anyString(), anyString())).thenReturn(user);
        TenantMember m = new TenantMember();
        m.setId(2L);
        m.setPid("mp");
        when(tenantMemberService.addMember(any(), any(), any())).thenReturn(m);

        Map<String, Object> stringIdRecord = new HashMap<>();
        stringIdRecord.put("id", "777");
        stringIdRecord.put("pid", "e1");
        when(dynamicDataService.create(eq("org_employee"), anyMap())).thenReturn(stringIdRecord);

        service.createWithUser(createRequest());
        assertEquals(777L, m.getEmployeeId());

        // Unparseable id
        Map<String, Object> bad = new HashMap<>();
        bad.put("id", new Object());
        bad.put("pid", "e1");
        when(dynamicDataService.create(eq("org_employee"), anyMap())).thenReturn(bad);
        assertThrows(BusinessException.class, () -> service.createWithUser(createRequest()));
    }
}
