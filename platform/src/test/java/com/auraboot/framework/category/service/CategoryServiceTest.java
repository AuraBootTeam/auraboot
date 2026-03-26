//package com.auraboot.framework.category.service;
//
//import com.auraboot.framework.application.TestApplication;
//import com.auraboot.framework.application.tenant.MetaContext;
//import com.auraboot.framework.category.entity.Category;
//import com.auraboot.framework.category.mapper.CategoryMapper;
//import com.auraboot.framework.common.util.UniqueIdGenerator;
//import com.auraboot.framework.tenant.dao.entity.Tenant;
//import com.auraboot.framework.tenant.dao.mapper.TenantMapper;
//import org.junit.jupiter.api.*;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.boot.test.context.SpringBootTest;
//import org.springframework.test.context.ActiveProfiles;
//import org.springframework.transaction.annotation.Transactional;
//
//import java.time.Instant;
//import java.util.List;
//import java.util.Map;
//
//import static org.junit.jupiter.api.Assertions.*;
//
///**
// * 类目服务测试类
// */
//@SpringBootTest(classes = TestApplication.class)
//@ActiveProfiles("test")
//@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
//public class CategoryServiceTest {
//
//    @Autowired
//    private CategoryService categoryService;
//
//    @Autowired
//    private CategoryMapper categoryMapper;
//
//    @Autowired
//    private TenantMapper tenantMapper;
//
//    private static Long testTenantId;
//    private static Long testUserId = 1L;
//
//    @BeforeAll
//    public static void setupAll(@Autowired TenantMapper tenantMapper) {
//        // 创建测试租户
//        Tenant tenant = new Tenant();
//        tenant.setName("test_tenant_category");
//        tenant.setDisplayName("测试租户-类目");
//        tenant.setStatus("active");
//        tenant.setCreatedAt(Instant.now());
//        tenant.setUpdatedAt(Instant.now());
//        tenant.setDeletedFlag(false);
//        tenant.setStatus("active");
//        tenant.setPid(UniqueIdGenerator.generate());
//        tenantMapper.insert(tenant);
//        testTenantId = tenant.getId();
//
//        MetaContext.setContext(testTenantId,"test","test",null,"test","test");
//    }
//
//    @AfterAll
//    public static void cleanAll() {
//        MetaContext.clear();
//    }
//
//    @Test
//    @Order(1)
//    @Transactional
//    public void testCreateRootCategory() {
//        // 创建一级类目
//        Category category = new Category();
//        category.setTenantId(testTenantId);
//        category.setName("电子产品");
//        category.setCode("electronics");
//        category.setCategoryType("product");
//        category.setDescription("电子产品类目");
//        category.setSortOrder(1);
//        category.setCreatedBy(testUserId);
//        category.setUpdatedBy(testUserId);
//
//        Category created = categoryService.createCategory(category);
//
//        assertNotNull(created);
//        assertNotNull(created.getId());
//        assertNotNull(created.getPid());
//        assertEquals("电子产品", created.getName());
//        assertEquals("electronics", created.getCode());
//        assertEquals(1, created.getLevel());
//        assertNull(created.getParentId());
//        assertFalse(created.isLeaf());
//        assertEquals("active", created.getStatus());
//    }
//
//    @Test
//    @Order(2)
//    @Transactional
//    public void testCreateChildCategory() {
//        // 先创建父类目
//        Category parent = new Category();
//        parent.setTenantId(testTenantId);
//        parent.setName("服装");
//        parent.setCode("clothing");
//        parent.setCategoryType("product");
//        parent.setSortOrder(2);
//        parent.setCreatedBy(testUserId);
//        parent.setUpdatedBy(testUserId);
//
//        Category createdParent = categoryService.createCategory(parent);
//
//        // 创建子类目
//        Category child = new Category();
//        child.setTenantId(testTenantId);
//        child.setParentId(createdParent.getId());
//        child.setName("男装");
//        child.setCode("mens_clothing");
//        child.setCategoryType("product");
//        child.setSortOrder(1);
//        child.setCreatedBy(testUserId);
//        child.setUpdatedBy(testUserId);
//        child.setLeaf(true);
//
//        Category createdChild = categoryService.createCategory(child);
//
//        assertNotNull(createdChild);
//        assertEquals(2, createdChild.getLevel());
//        assertEquals(createdParent.getId(), createdChild.getParentId());
//        assertTrue(createdChild.isLeaf());
//
//        // 验证父类目的 is_leaf 已更新
//        Category updatedParent = categoryService.getById(createdParent.getId());
//        assertFalse(updatedParent.isLeaf());
//    }
//
//    @Test
//    @Order(3)
//    @Transactional
//    public void testCreateThirdLevelCategoryShouldFail() {
//        // 创建一级类目
//        Category level1 = new Category();
//        level1.setTenantId(testTenantId);
//        level1.setName("数码");
//        level1.setCode("digital");
//        level1.setCategoryType("product");
//        level1.setCreatedBy(testUserId);
//        level1.setUpdatedBy(testUserId);
//        Category createdLevel1 = categoryService.createCategory(level1);
//
//        // 创建二级类目
//        Category level2 = new Category();
//        level2.setTenantId(testTenantId);
//        level2.setParentId(createdLevel1.getId());
//        level2.setName("手机");
//        level2.setCode("phone");
//        level2.setCategoryType("product");
//        level2.setCreatedBy(testUserId);
//        level2.setUpdatedBy(testUserId);
//        Category createdLevel2 = categoryService.createCategory(level2);
//
//        // 尝试创建三级类目（应该失败）
//        Category level3 = new Category();
//        level3.setTenantId(testTenantId);
//        level3.setParentId(createdLevel2.getId());
//        level3.setName("智能手机");
//        level3.setCode("smartphone");
//        level3.setCategoryType("product");
//        level3.setCreatedBy(testUserId);
//        level3.setUpdatedBy(testUserId);
//
//        Exception exception = assertThrows(RuntimeException.class, () -> {
//            categoryService.createCategory(level3);
//        });
//
//        assertTrue(exception.getMessage().contains("不支持三级及以上类目"));
//    }
//
//    @Test
//    @Order(4)
//    @Transactional
//    public void testCodeUniqueConstraint() {
//        // 创建第一个类目
//        Category category1 = new Category();
//        category1.setTenantId(testTenantId);
//        category1.setName("家电");
//        category1.setCode("home_appliance");
//        category1.setCategoryType("product");
//        category1.setCreatedBy(testUserId);
//        category1.setUpdatedBy(testUserId);
//        categoryService.createCategory(category1);
//
//        // 尝试创建相同编码的类目（应该失败）
//        Category category2 = new Category();
//        category2.setTenantId(testTenantId);
//        category2.setName("家电2");
//        category2.setCode("home_appliance");
//        category2.setCategoryType("product");
//        category2.setCreatedBy(testUserId);
//        category2.setUpdatedBy(testUserId);
//
//        Exception exception = assertThrows(RuntimeException.class, () -> {
//            categoryService.createCategory(category2);
//        });
//
//        assertTrue(exception.getMessage().contains("类目编码在该租户下已存在"));
//    }
//
//    @Test
//    @Order(5)
//    @Transactional
//    public void testDeleteCategoryWithChildren() {
//        // 创建父类目
//        Category parent = new Category();
//        parent.setTenantId(testTenantId);
//        parent.setName("图书");
//        parent.setCode("books");
//        parent.setCategoryType("product");
//        parent.setCreatedBy(testUserId);
//        parent.setUpdatedBy(testUserId);
//        Category createdParent = categoryService.createCategory(parent);
//
//        // 创建子类目
//        Category child = new Category();
//        child.setTenantId(testTenantId);
//        child.setParentId(createdParent.getId());
//        child.setName("小说");
//        child.setCode("novel");
//        child.setCategoryType("product");
//        child.setCreatedBy(testUserId);
//        child.setUpdatedBy(testUserId);
//        categoryService.createCategory(child);
//
//        // 尝试删除有子类目的父类目（应该失败）
//        Exception exception = assertThrows(RuntimeException.class, () -> {
//            categoryService.deleteCategory(createdParent.getId());
//        });
//
//        assertTrue(exception.getMessage().contains("存在子类目，无法删除"));
//    }
//
//    @Test
//    @Order(6)
//    @Transactional
//    public void testDeleteLeafCategory() {
//        // 创建类目
//        Category category = new Category();
//        category.setTenantId(testTenantId);
//        category.setName("测试删除");
//        category.setCode("test_delete");
//        category.setCategoryType("product");
//        category.setCreatedBy(testUserId);
//        category.setUpdatedBy(testUserId);
//        Category created = categoryService.createCategory(category);
//
//        // 删除叶子节点
//        boolean result = categoryService.deleteCategory(created.getId());
//        assertTrue(result);
//
//        // 验证已被逻辑删除
//        Category deleted = categoryService.getById(created.getId());
//        assertNull(deleted); // 因为逻辑删除，getById应该返回null
//    }
//
//    @Test
//    @Order(7)
//    @Transactional
//    public void testUpdateCategory() {
//        // 创建类目
//        Category category = new Category();
//        category.setTenantId(testTenantId);
//        category.setName("原始名称");
//        category.setCode("original");
//        category.setCategoryType("product");
//        category.setCreatedBy(testUserId);
//        category.setUpdatedBy(testUserId);
//        Category created = categoryService.createCategory(category);
//
//        // 更新类目
//        created.setName("更新后的名称");
//        created.setDescription("添加描述");
//        Category updated = categoryService.updateCategory(created);
//
//        assertEquals("更新后的名称", updated.getName());
//        assertEquals("添加描述", updated.getDescription());
//        assertEquals("original", updated.getCode());
//    }
//
//    @Test
//    @Order(8)
//    @Transactional
//    public void testEnableAndDisableCategory() {
//        // 创建类目
//        Category category = new Category();
//        category.setTenantId(testTenantId);
//        category.setName("测试状态");
//        category.setCode("test_status");
//        category.setCategoryType("product");
//        category.setCreatedBy(testUserId);
//        category.setUpdatedBy(testUserId);
//        Category created = categoryService.createCategory(category);
//
//        // 禁用
//        boolean disabled = categoryService.disableCategory(created.getId());
//        assertTrue(disabled);
//
//        Category disabledCategory = categoryMapper.selectById(created.getId());
//        assertEquals("inactive", disabledCategory.getStatus());
//
//        // 启用
//        boolean enabled = categoryService.enableCategory(created.getId());
//        assertTrue(enabled);
//
//        Category enabledCategory = categoryMapper.selectById(created.getId());
//        assertEquals("active", enabledCategory.getStatus());
//    }
//
//    @Test
//    @Order(9)
//    @Transactional
//    public void testGetCategoryTree() {
//        // 创建一级类目
//        Category root1 = new Category();
//        root1.setTenantId(testTenantId);
//        root1.setName("美食");
//        root1.setCode("food");
//        root1.setCategoryType("product");
//        root1.setSortOrder(1);
//        root1.setCreatedBy(testUserId);
//        root1.setUpdatedBy(testUserId);
//        Category createdRoot1 = categoryService.createCategory(root1);
//
//        // 创建二级类目
//        Category child1 = new Category();
//        child1.setTenantId(testTenantId);
//        child1.setParentId(createdRoot1.getId());
//        child1.setName("中餐");
//        child1.setCode("chinese_food");
//        child1.setCategoryType("product");
//        child1.setSortOrder(1);
//        child1.setCreatedBy(testUserId);
//        child1.setUpdatedBy(testUserId);
//        categoryService.createCategory(child1);
//
//        Category child2 = new Category();
//        child2.setTenantId(testTenantId);
//        child2.setParentId(createdRoot1.getId());
//        child2.setName("西餐");
//        child2.setCode("western_food");
//        child2.setCategoryType("product");
//        child2.setSortOrder(2);
//        child2.setCreatedBy(testUserId);
//        child2.setUpdatedBy(testUserId);
//        categoryService.createCategory(child2);
//
//        // 获取类目树
//        List<Map<String, Object>> tree = categoryService.getCategoryTree(testTenantId);
//
//        assertNotNull(tree);
//        assertTrue(tree.size() > 0);
//
//        // 查找美食类目
//        Map<String, Object> foodNode = tree.stream()
//                .filter(node -> "food".equals(node.get("code")))
//                .findFirst()
//                .orElse(null);
//
//        assertNotNull(foodNode);
//        assertEquals("美食", foodNode.get("name"));
//
//        @SuppressWarnings("unchecked")
//        List<Map<String, Object>> children = (List<Map<String, Object>>) foodNode.get("children");
//        assertNotNull(children);
//        assertEquals(2, children.size());
//    }
//
//    @Test
//    @Order(10)
//    @Transactional
//    public void testMoveCategory() {
//        // 创建两个一级类目
//        Category root1 = new Category();
//        root1.setTenantId(testTenantId);
//        root1.setName("运动");
//        root1.setCode("sports");
//        root1.setCategoryType("product");
//        root1.setCreatedBy(testUserId);
//        root1.setUpdatedBy(testUserId);
//        Category createdRoot1 = categoryService.createCategory(root1);
//
//        Category root2 = new Category();
//        root2.setTenantId(testTenantId);
//        root2.setName("户外");
//        root2.setCode("outdoor");
//        root2.setCategoryType("product");
//        root2.setCreatedBy(testUserId);
//        root2.setUpdatedBy(testUserId);
//        Category createdRoot2 = categoryService.createCategory(root2);
//
//        // 在root1下创建子类目
//        Category child = new Category();
//        child.setTenantId(testTenantId);
//        child.setParentId(createdRoot1.getId());
//        child.setName("篮球");
//        child.setCode("basketball");
//        child.setCategoryType("product");
//        child.setCreatedBy(testUserId);
//        child.setUpdatedBy(testUserId);
//        Category createdChild = categoryService.createCategory(child);
//
//        // 移动到root2下
//        boolean result = categoryService.moveCategory(createdChild.getId(), createdRoot2.getId());
//        assertTrue(result);
//
//        // 验证移动后的状态
//        Category movedChild = categoryService.getById(createdChild.getId());
//        assertEquals(createdRoot2.getId(), movedChild.getParentId());
//        assertEquals(2, movedChild.getLevel());
//    }
//
//    @Test
//    @Order(11)
//    @Transactional
//    public void testUpdateSortOrder() {
//        // 创建类目
//        Category category = new Category();
//        category.setTenantId(testTenantId);
//        category.setName("测试排序");
//        category.setCode("test_sort");
//        category.setCategoryType("product");
//        category.setSortOrder(1);
//        category.setCreatedBy(testUserId);
//        category.setUpdatedBy(testUserId);
//        Category created = categoryService.createCategory(category);
//
//        // 更新排序
//        boolean result = categoryService.updateSortOrder(created.getId(), 10);
//        assertTrue(result);
//
//        // 验证
//        Category updated = categoryMapper.selectById(created.getId());
//        assertEquals(10, updated.getSortOrder());
//    }
//
//    @Test
//    @Order(12)
//    @Transactional
//    public void testIsCodeAvailable() {
//        // 创建类目
//        Category category = new Category();
//        category.setTenantId(testTenantId);
//        category.setName("测试编码");
//        category.setCode("test_code_available");
//        category.setCategoryType("product");
//        category.setCreatedBy(testUserId);
//        category.setUpdatedBy(testUserId);
//        categoryService.createCategory(category);
//
//        // 测试已存在的编码
//        boolean available1 = categoryService.isCodeAvailable("test_code_available", testTenantId);
//        assertFalse(available1);
//
//        // 测试不存在的编码
//        boolean available2 = categoryService.isCodeAvailable("not_exist_code", testTenantId);
//        assertTrue(available2);
//    }
//
//    @Test
//    @Order(13)
//    @Transactional
//    public void testHasChildren() {
//        // 创建父类目
//        Category parent = new Category();
//        parent.setTenantId(testTenantId);
//        parent.setName("父类目");
//        parent.setCode("parent_cat");
//        parent.setCategoryType("product");
//        parent.setCreatedBy(testUserId);
//        parent.setUpdatedBy(testUserId);
//        Category createdParent = categoryService.createCategory(parent);
//
//        // 初始状态没有子类目
//        assertFalse(categoryService.hasChildren(createdParent.getId()));
//
//        // 添加子类目
//        Category child = new Category();
//        child.setTenantId(testTenantId);
//        child.setParentId(createdParent.getId());
//        child.setName("子类目");
//        child.setCode("child_cat");
//        child.setCategoryType("product");
//        child.setCreatedBy(testUserId);
//        child.setUpdatedBy(testUserId);
//        categoryService.createCategory(child);
//
//        // 现在应该有子类目
//        assertTrue(categoryService.hasChildren(createdParent.getId()));
//    }
//
//    @Test
//    @Order(14)
//    @Transactional
//    public void testBulkInsertCategories() {
//        List<Category> categories = List.of(
//                createTestCategory("批量1", "bulk_1", testTenantId),
//                createTestCategory("批量2", "bulk_2", testTenantId),
//                createTestCategory("批量3", "bulk_3", testTenantId)
//        );
//
//        int count = categoryService.bulkInsertCategories(categories);
//        assertEquals(3, count);
//
//        // 验证插入成功
//        Category category = categoryService.findByCodeAndTenantId("bulk_1", testTenantId);
//        assertNotNull(category);
//        assertEquals("批量1", category.getName());
//    }
//
//    private Category createTestCategory(String name, String code, Long tenantId) {
//        Category category = new Category();
//        category.setTenantId(tenantId);
//        category.setName(name);
//        category.setCode(code);
//        category.setLevel(1);
//        category.setCategoryType("product");
//        category.setCreatedBy(testUserId);
//        category.setUpdatedBy(testUserId);
//        return category;
//    }
//}
