package com.auraboot.framework.integration;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.constant.DataType;
import com.auraboot.framework.rbac.entity.*;
import com.auraboot.framework.menu.entity.Menu;
import com.auraboot.framework.menu.constant.MenuStatus;
import java.util.*;

/**
 * Device Model 集成测试辅助方法
 * 支持新的分离测试类结构，提供各种测试数据创建方法
 */

/**
 * Device Model 集成测试辅助方法
 */
public class DeviceModelTestHelpers {

    /**
     * 创建设备类型字典请求
     */
    public static DictCreateRequest createDeviceTypeDict() {
        DictCreateRequest request = new DictCreateRequest();
        request.setCode("device_type_dict");
        request.setName("设备类型");
        request.setDescription("IoT设备类型分类");
        request.setDictType("static");
        request.setSourceType("manual");
          

        return request;
    }

    /**
     * 创建制造商字典请求
     */
    public static DictCreateRequest createManufacturerDict() {
        DictCreateRequest request = new DictCreateRequest();
        request.setCode("manufacturer_dict");
        request.setName("制造商");
        request.setDescription("设备制造商列表");
        request.setDictType("static");
        request.setSourceType("manual");
          
        

        return request;
    }

    /**
     * 创建安装位置字典请求（动态级联字典）
     */
    public static DictCreateRequest createLocationDict() {
        DictCreateRequest request = new DictCreateRequest();
        request.setCode("location_dict");
        request.setName("安装位置");
        request.setDescription("设备安装位置（按制造商级联）");
        request.setDictType("dynamic");
        request.setSourceType("manual");

        return request;
    }

    /**
     * 创建设备状态字典请求
     */
    public static DictCreateRequest createDeviceStatusDict() {
        DictCreateRequest request = new DictCreateRequest();
        request.setCode("device_status_dict");
        request.setName("设备状态");
        request.setDescription("设备运行状态");
        request.setDictType("static");
        request.setSourceType("manual");
          
        

        return request;
    }

    /**
     * 创建设备模型请求
     */
    public static MetaModelCreateRequest createDeviceModelRequest() {
        MetaModelCreateRequest request = new MetaModelCreateRequest();
        request.setCode("device");
        request.setDisplayName("设备管理");
        request.setDescription("IoT设备信息管理");
        request.setModelType("entity");
          
        
        // 设置租户ID - 从MetaContext获取
        request.setTenantId(com.auraboot.framework.application.tenant.MetaContext.getCurrentTenantId());
        
        return request;
    }

    /**
     * 创建基础字段
     */
    public static MetaFieldCreateRequest createFieldRequest(String fieldCode, String fieldName, 
                                                           DataType dataType, boolean required, boolean primaryKey) {
        MetaFieldCreateRequest request = new MetaFieldCreateRequest();
        request.setCode(fieldCode);
        request.setDataType(dataType.getCode());
          
        
        
        // 设置字段特性
        Map<String, Object> feature = new HashMap<>();
        feature.put("required", required);
        feature.put("primaryKey", primaryKey);
        feature.put("displayName", fieldName);
        
        if (dataType == DataType.STRING) {
            feature.put("maxLength", primaryKey ? 50 : 100);
        } else if (dataType == DataType.DECIMAL) {
            feature.put("precision", 10);
            feature.put("scale", 2);
        }
        
        request.setFeature(feature);
        
        // 设置扩展字段 - 必须提供，否则数据库会报NOT NULL约束错误
        Map<String, Object> extension = new HashMap<>();
        extension.put("createdBy", "integration-test");
        extension.put("fieldType", "basic");
        extension.put("category", "device-model");
        request.setExtension(extension);
        
        return request;
    }

    /**
     * 创建测试设备数据
     */
    public static List<Map<String, Object>> createTestDeviceData() {
        List<Map<String, Object>> devices = new ArrayList<>();

        // 设备1: 西门子传感器
        Map<String, Object> device1 = new HashMap<>();
        device1.put("device_id", "device001");
        device1.put("device_name", "温度传感器-001");
        device1.put("device_type", "sensor");
        device1.put("manufacturer", "siemens");
        device1.put("status", "online");
        device1.put("install_date", "2024-01-15");
        device1.put("warranty_expire", "2026-01-15");
        device1.put("price", 1500.00);
        device1.put("serial_number", "sn20240115001");
        device1.put("description", "高精度温度传感器，用于车间环境监控");
        devices.add(device1);

        // 设备2: ABB执行器
        Map<String, Object> device2 = new HashMap<>();
        device2.put("device_id", "device002");
        device2.put("device_name", "电动执行器-002");
        device2.put("device_type", "actuator");
        device2.put("manufacturer", "abb");
        device2.put("status", "maintenance");
        device2.put("install_date", "2024-02-20");
        device2.put("warranty_expire", "2027-02-20");
        device2.put("price", 3200.50);
        device2.put("serial_number", "sn20240220002");
        device2.put("description", "高扭矩电动执行器，用于阀门控制");
        devices.add(device2);

        // 设备3: 施耐德控制器
        Map<String, Object> device3 = new HashMap<>();
        device3.put("device_id", "device003");
        device3.put("device_name", "PLC控制器-003");
        device3.put("device_type", "controller");
        device3.put("manufacturer", "schneider");
        device3.put("status", "online");
        device3.put("install_date", "2024-03-10");
        device3.put("warranty_expire", "2029-03-10");
        device3.put("price", 5800.00);
        device3.put("serial_number", "sn20240310003");
        device3.put("description", "可编程逻辑控制器，用于生产线自动化控制");
        devices.add(device3);

        return devices;
    }

    /**
     * 创建设备列表查询
     */
    public static NamedQueryCreateRequest createDeviceListQuery() {
        NamedQueryCreateRequest request = new NamedQueryCreateRequest();
         
        request.setCode("device_list_query");
        request.setTitle("设备列表查询");
        request.setDescription("支持多条件的设备列表查询");
        
        // FROM子句SQL
        request.setFromSql(
            "ab_entity_records WHERE entity_code = 'device'"
        );
        
        return request;
    }

    /**
     * 创建设备统计查询
     */
    public static NamedQueryCreateRequest createDeviceStatisticsQuery() {
        NamedQueryCreateRequest request = new NamedQueryCreateRequest();
         
        request.setCode("device_statistics_query");
        request.setTitle("设备统计查询");
        request.setDescription("按类型和状态统计设备数量");
        
        // FROM子句SQL
        request.setFromSql(
            "ab_entity_records WHERE entity_code = 'device'"
        );
        
        return request;
    }

    // ==================== 权限系统测试辅助方法（简化版） ====================

    /**
     * 创建测试角色列表
     */
    public static List<Role> createTestRoles() {
        List<Role> roles = new ArrayList<>();
        
        // 设备管理员角色
        Role adminRole = new Role();
        adminRole.setPid(generatePid());
        adminRole.setCode("device_admin");
        adminRole.setName("设备管理员");
        adminRole.setDescription("设备管理的完全权限");
        adminRole.setType("business");
        adminRole.setStatus("active");
        roles.add(adminRole);
        
        // 设备操作员角色
        Role operatorRole = new Role();
        operatorRole.setPid(generatePid());
        operatorRole.setCode("device_operator");
        operatorRole.setName("设备操作员");
        operatorRole.setDescription("设备的查看和基本操作权限");
        operatorRole.setType("business");
        operatorRole.setStatus("active");
        roles.add(operatorRole);
        
        // 设备查看者角色
        Role viewerRole = new Role();
        viewerRole.setPid(generatePid());
        viewerRole.setCode("device_viewer");
        viewerRole.setName("设备查看者");
        viewerRole.setDescription("设备的只读权限");
        viewerRole.setType("business");
        viewerRole.setStatus("active");
        roles.add(viewerRole);
        
        return roles;
    }

    /**
     * 创建测试Permission列表
     * 替代Permission系统,使用Permission架构
     */
    public static List<com.auraboot.framework.permission.entity.Permission> createTestPermissions() {
        List<com.auraboot.framework.permission.entity.Permission> permissions = new ArrayList<>();
        
        // 实体级Permission
        permissions.add(createPermission("MODEL.device.list", "设备列表查看", "model", "device", "list"));
        permissions.add(createPermission("MODEL.device.view", "设备详情查看", "model", "device", "view"));
        permissions.add(createPermission("MODEL.device.create", "设备创建", "model", "device", "create"));
        permissions.add(createPermission("MODEL.device.update", "设备更新", "model", "device", "update"));
        permissions.add(createPermission("MODEL.device.delete", "设备删除", "model", "device", "delete"));
        permissions.add(createPermission("MODEL.device.export", "设备导出", "model", "device", "export"));
        permissions.add(createPermission("MODEL.device.import", "设备导入", "model", "device", "import"));
        
        // 字段级Permission
        permissions.add(createPermission("FIELD.device.price.view", "价格字段查看", "field", "device.price", "view"));
        permissions.add(createPermission("FIELD.device.price.edit", "价格字段编辑", "field", "device.price", "edit"));
        permissions.add(createPermission("FIELD.device.serial_number.view", "序列号字段查看", "field", "device.serial_number", "view"));
        permissions.add(createPermission("FIELD.device.serial_number.edit", "序列号字段编辑", "field", "device.serial_number", "edit"));
        
        // 字典级Permission
        permissions.add(createPermission("DICT.device_type_dict.read", "设备类型字典读取", "dict", "device_type_dict", "read"));
        permissions.add(createPermission("DICT.device_type_dict.manage", "设备类型字典管理", "dict", "device_type_dict", "manage"));
        permissions.add(createPermission("DICT.manufacturer_dict.read", "制造商字典读取", "dict", "manufacturer_dict", "read"));
        permissions.add(createPermission("DICT.manufacturer_dict.manage", "制造商字典管理", "dict", "manufacturer_dict", "manage"));
        
        return permissions;
    }
    
    /**
     * 创建Permission对象
     */
    private static com.auraboot.framework.permission.entity.Permission createPermission(
            String code, String name, String resourceType, String resourceCode, String action) {
        com.auraboot.framework.permission.entity.Permission permission = new com.auraboot.framework.permission.entity.Permission();
        permission.setPid(generatePid());
        permission.setCode(code);
        permission.setName(name);
        permission.setDescription(name);
        permission.setResourceType(resourceType);
        permission.setResourceCode(resourceCode);
        permission.setAction(action);
        permission.setSource("manual");
        permission.setStatus("active");
        permission.setLevel(0);
        permission.setDeletedFlag(false);
        permission.setCreatedAt(java.time.Instant.now());
        permission.setUpdatedAt(java.time.Instant.now());
        return permission;
    }
    
    /**
     * 创建角色Permission关联
     * 替代RolePermission系统
     */
    public static List<com.auraboot.framework.rbac.entity.RolePermission> createRolePermissionMappings(
            Map<String, Long> roleIdMap, Map<String, Long> permissionIdMap) {
        List<com.auraboot.framework.rbac.entity.RolePermission> mappings = new ArrayList<>();
        
        // 设备管理员 - 所有Permission
        Long adminRoleId = roleIdMap.get("device_admin");
        for (Long permissionId : permissionIdMap.values()) {
            mappings.add(createRolePermission(adminRoleId, permissionId, "grant"));
        }
        
        // 设备操作员 - 基本操作Permission,价格字段编辑拒绝
        Long operatorRoleId = roleIdMap.get("device_operator");
        mappings.add(createRolePermission(operatorRoleId, permissionIdMap.get("MODEL.device.list"), "grant"));
        mappings.add(createRolePermission(operatorRoleId, permissionIdMap.get("MODEL.device.view"), "grant"));
        mappings.add(createRolePermission(operatorRoleId, permissionIdMap.get("MODEL.device.create"), "grant"));
        mappings.add(createRolePermission(operatorRoleId, permissionIdMap.get("MODEL.device.update"), "grant"));
        mappings.add(createRolePermission(operatorRoleId, permissionIdMap.get("MODEL.device.export"), "grant"));
        mappings.add(createRolePermission(operatorRoleId, permissionIdMap.get("FIELD.device.price.edit"), "deny"));
        
        // 设备查看者 - 只读Permission,敏感字段拒绝
        Long viewerRoleId = roleIdMap.get("device_viewer");
        mappings.add(createRolePermission(viewerRoleId, permissionIdMap.get("MODEL.device.list"), "grant"));
        mappings.add(createRolePermission(viewerRoleId, permissionIdMap.get("MODEL.device.view"), "grant"));
        mappings.add(createRolePermission(viewerRoleId, permissionIdMap.get("FIELD.device.price.view"), "deny"));
        
        return mappings;
    }
    
    /**
     * 创建角色Permission关联对象
     */
    private static com.auraboot.framework.rbac.entity.RolePermission createRolePermission(
            Long roleId, Long permissionId, String grantType) {
        com.auraboot.framework.rbac.entity.RolePermission rc = new com.auraboot.framework.rbac.entity.RolePermission();
        rc.setPid(generatePid());
        rc.setRoleId(roleId);
        rc.setPermissionId(permissionId);
        rc.setGrantType(grantType);
        rc.setStatus("active");
        rc.setPriority(0);
        rc.setDeletedFlag(false);
        rc.setCreatedAt(java.time.Instant.now());
        rc.setUpdatedAt(java.time.Instant.now());
        return rc;
    }




    /**
     * 创建用户角色关联
     */
    public static List<UserRole> createUserRoleMappings(String userPid, Map<String, Long> roleIdMap,Long tenantId) {
        List<UserRole> mappings = new ArrayList<>();
        
        // 为测试用户分配设备管理员角色
        UserRole userRole = new UserRole();
        userRole.setPid(generatePid()); // 设置PID避免唯一约束冲突
        userRole.setMemberId(Long.valueOf(userPid.hashCode())); // Simplified synthetic member ID for test fixtures
        userRole.setRoleId(roleIdMap.get("device_admin"));
        userRole.setTenantId(tenantId);
        userRole.setStatus("active");
        mappings.add(userRole);
        
        return mappings;
    }

    // ==================== 菜单系统测试辅助方法（简化版） ====================

    /**
     * 创建设备管理菜单
     */
    public static List<Menu> createDeviceMenus() {
        List<Menu> menus = new ArrayList<>();
        
        // 设备管理父菜单
        Menu deviceManagement = new Menu();
        deviceManagement.setPid(generatePid());
        deviceManagement.setName("设备管理");
        deviceManagement.setType(0); // 目录
        deviceManagement.setIcon("device-mobile");
        deviceManagement.setOrderNo(100);
        deviceManagement.setStatus(MenuStatus.ACTIVE);
        menus.add(deviceManagement);
        
        // 设备列表菜单
        Menu deviceList = new Menu();
        deviceList.setPid(generatePid());
        deviceList.setName("设备列表");
        deviceList.setType(1); // 菜单
        deviceList.setPath("/device/list");
        deviceList.setIcon("list");
        deviceList.setOrderNo(101);
        deviceList.setStatus(MenuStatus.ACTIVE);
        deviceList.setPermissionCode("dev:list");
        menus.add(deviceList);
        
        // 新增设备菜单
        Menu deviceCreate = new Menu();
        deviceCreate.setPid(generatePid());
        deviceCreate.setName("新增设备");
        deviceCreate.setType(1); // 菜单
        deviceCreate.setPath("/device/create");
        deviceCreate.setIcon("plus");
        deviceCreate.setOrderNo(102);
        deviceCreate.setStatus(MenuStatus.ACTIVE);
        deviceCreate.setPermissionCode("dev:create");
        menus.add(deviceCreate);
        
        // 设备统计菜单
        Menu deviceStats = new Menu();
        deviceStats.setPid(generatePid());
        deviceStats.setName("设备统计");
        deviceStats.setType(1); // 菜单
        deviceStats.setPath("/device/statistics");
        deviceStats.setIcon("chart-bar");
        deviceStats.setOrderNo(103);
        deviceStats.setStatus(MenuStatus.ACTIVE);
        deviceStats.setPermissionCode("dev:list");
        menus.add(deviceStats);
        
        return menus;
    }

    // ==================== 辅助工具方法 ====================
    
    /**
     * 生成PID - 限制长度以符合数据库字段限制
     */
    private static String generatePid() {
       return UniqueIdGenerator.generate();
    }

    // ==================== CRUD测试数据辅助方法 ====================

    /**
     * 创建大量测试设备数据（用于性能测试）
     */
    public static List<Map<String, Object>> createLargeTestDataSet(int count) {
        List<Map<String, Object>> devices = new ArrayList<>();
        String[] types = {"sensor", "actuator", "controller", "gateway"};
        String[] manufacturers = {"siemens", "abb", "schneider", "honeywell"};
        String[] statuses = {"online", "offline", "maintenance", "fault"};
        
        for (int i = 1; i <= count; i++) {
            Map<String, Object> device = new HashMap<>();
            device.put("device_id", String.format("DEVICE%06d", i));
            device.put("device_name", String.format("设备-%06d", i));
            device.put("device_type", types[i % types.length]);
            device.put("manufacturer", manufacturers[i % manufacturers.length]);
            device.put("status", statuses[i % statuses.length]);
            device.put("install_date", "2024-01-01");
            device.put("warranty_expire", "2027-01-01");
            device.put("price", 1000.0 + (i % 5000));
            device.put("serial_number", String.format("SN%010d", i));
            device.put("description", String.format("测试设备%d，用于集成测试", i));
            devices.add(device);
        }
        
        return devices;
    }

    /**
     * 创建无效的设备数据（用于验证测试）
     */
    public static Map<String, Object> createInvalidDeviceData() {
        Map<String, Object> device = new HashMap<>();
        device.put("device_id", ""); // 必填字段为空
        device.put("device_name", ""); // 必填字段为空
        device.put("install_date", "invalid-date"); // 无效日期格式
        device.put("price", "not-a-number"); // 无效数字格式
        return device;
    }

    /**
     * 创建SQL注入攻击数据
     */
    public static Map<String, Object> createMaliciousQueryConditions() {
        Map<String, Object> conditions = new HashMap<>();
        conditions.put("device_name", "'; DROP TABLE ab_entity_records; --");
        conditions.put("device_type", "' OR '1'='1");
        conditions.put("manufacturer", "'; DELETE FROM ab_entity_records WHERE '1'='1");
        return conditions;
    }

    /**
     * 创建分页请求
     */
    public static PaginationRequest createPaginationRequest(int pageNum, int pageSize) {
        PaginationRequest request = new PaginationRequest();
        request.setPageNum(pageNum);
        request.setPageSize(pageSize);
        return request;
    }

    /**
     * 创建带条件的分页请求（简化版，不使用conditions字段）
     */
    public static PaginationRequest createPaginationRequestWithConditions(
            int pageNum, int pageSize, Map<String, Object> conditions) {
        PaginationRequest request = createPaginationRequest(pageNum, pageSize);
        // 注意：PaginationRequest没有conditions字段，这里只是为了测试结构
        // 实际使用时需要通过其他方式传递查询条件
        return request;
    }
}
