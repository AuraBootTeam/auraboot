package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.DictDataResult;
import com.auraboot.framework.meta.entity.Dict;
import com.auraboot.framework.meta.entity.DictItem;
import com.auraboot.framework.meta.entity.payload.DataSourceItemBean;
import com.auraboot.framework.meta.mapper.DictItemMapper;
import com.auraboot.framework.meta.mapper.DictMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * DictVersionService STATIC/DYNAMIC字典加载单元测试
 * 
 * 测试范围:
 * - STATIC字典从DSL加载(items字段)
 * - DYNAMIC字典从ab_dict_item表加载
 * - 加载失败场景处理
 * - 缓存更新机制
 * 
 * 验证需求:
 * - 需求 6.1: STATIC字典从Git DSL投影后的dict记录中读取items字段
 * - 需求 6.2: STATIC字典加载成功返回DSL中定义的完整字典项列表
 * - 需求 6.3: STATIC字典缺少items定义时记录错误日志并抛出异常
 * - 需求 6.4: DYNAMIC字典从ab_dict_item表加载字典项数据
 * - 需求 6.5: 测试缓存更新
 * 
 * @author AuraBoot Team
 * @since 2.1.2
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DictVersionService STATIC/DYNAMIC字典加载测试")
class DictVersionServiceStaticDictTest {

    @Mock
    private DictMapper dictMapper;
    
    @Mock
    private DictItemMapper dictItemMapper;
    
    @InjectMocks
    private DictVersionServiceImpl dictVersionService;
    
    private ObjectMapper objectMapper;
    
    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        
        // 设置租户上下文
        MetaContext.setContext(1L, 100L, null, null);
    }
    
    // ==================== STATIC字典加载测试 ====================
    
    @Test
    @DisplayName("测试STATIC字典从DSL加载成功")
    void testLoadStaticDictSuccess() {
        // Given - 创建STATIC字典,items在Git DSL中定义
        Dict dict = createStaticDict("user_status", "用户状态");
        
        // 模拟Git DSL投影后的items数据
        List<DataSourceItemBean> items = new ArrayList<>();
        items.add(createDataSourceItem("active", "激活", 1));
        items.add(createDataSourceItem("disabled", "禁用", 2));
        items.add(createDataSourceItem("locked", "锁定", 3));
        dict.setItems(items);
        
        // When
        DictDataResult result = dictVersionService.loadStaticDictData(dict);
        
        // Then
        assertNotNull(result);
        assertTrue(result.getSuccess());
        assertEquals("user_status", result.getCode());
        assertEquals("用户状态", result.getName());
        assertEquals("static", result.getDictType());
        
        // 验证items加载正确
        assertNotNull(result.getItems());
        assertEquals(3, result.getItems().size());
        
        // 验证第一个item
        DictDataResult.DictItemData firstItem = result.getItems().get(0);
        assertEquals("active", firstItem.getValue());
        assertEquals("激活", firstItem.getLabel());
        assertEquals(1, firstItem.getSortOrder());
        assertTrue(firstItem.getEnabled());
        
        // 验证itemMap
        assertNotNull(result.getItemMap());
        assertEquals(3, result.getItemMap().size());
        assertEquals("激活", result.getItemMap().get("active"));
        assertEquals("禁用", result.getItemMap().get("disabled"));
        assertEquals("锁定", result.getItemMap().get("locked"));
    }
    
    @Test
    @DisplayName("测试STATIC字典缺少items定义")
    void testLoadStaticDictMissingItems() {
        // Given - STATIC字典但没有items
        Dict dict = createStaticDict("user_status", "用户状态");
        dict.setItems(null); // 缺少items
        
        // When
        DictDataResult result = dictVersionService.loadStaticDictData(dict);
        
        // Then
        assertNotNull(result);
        assertFalse(result.getSuccess());
        assertEquals("STATIC字典缺少items定义", result.getErrorMessage());
    }
    
    @Test
    @DisplayName("测试STATIC字典items为空列表")
    void testLoadStaticDictEmptyItems() {
        // Given - STATIC字典但items为空列表
        Dict dict = createStaticDict("user_status", "用户状态");
        dict.setItems(new ArrayList<>()); // 空列表
        
        // When
        DictDataResult result = dictVersionService.loadStaticDictData(dict);
        
        // Then
        assertNotNull(result);
        assertFalse(result.getSuccess());
        assertEquals("STATIC字典缺少items定义", result.getErrorMessage());
    }
    
    @Test
    @DisplayName("测试STATIC字典类型错误")
    void testLoadStaticDictWrongType() {
        // Given - 字典类型不是STATIC
        Dict dict = createDynamicDict("department", "部门");
        
        // When
        DictDataResult result = dictVersionService.loadStaticDictData(dict);
        
        // Then
        assertNotNull(result);
        assertFalse(result.getSuccess());
        assertTrue(result.getErrorMessage().contains("字典类型不是STATIC"));
    }
    
    @Test
    @DisplayName("测试STATIC字典items包含扩展属性")
    void testLoadStaticDictWithExtendedProps() {
        // Given
        Dict dict = createStaticDict("priority", "优先级");
        
        List<DataSourceItemBean> items = new ArrayList<>();
        DataSourceItemBean item = createDataSourceItem("high", "高", 1);
        
        // 添加扩展属性
        Map<String, Object> extra = new HashMap<>();
        extra.put("color", "red");
        extra.put("weight", 100);
        item.setExtra(extra);
        
        items.add(item);
        dict.setItems(items);
        
        // When
        DictDataResult result = dictVersionService.loadStaticDictData(dict);
        
        // Then
        assertTrue(result.getSuccess());
        assertEquals(1, result.getItems().size());
        
        DictDataResult.DictItemData loadedItem = result.getItems().get(0);
        assertNotNull(loadedItem.getExtension());
        @SuppressWarnings("unchecked")
        Map<String, Object> ext = (Map<String, Object>) loadedItem.getExtension();
        assertEquals("red", ext.get("color"));
        assertEquals(100, ext.get("weight"));
    }
    
    @Test
    @DisplayName("测试STATIC字典items包含禁用项")
    void testLoadStaticDictWithDisabledItems() {
        // Given
        Dict dict = createStaticDict("user_status", "用户状态");
        
        List<DataSourceItemBean> items = new ArrayList<>();
        items.add(createDataSourceItem("active", "激活", 1));
        
        DataSourceItemBean disabledItem = createDataSourceItem("deleted", "已删除", 2);
        disabledItem.setDisabled(true); // 禁用项
        items.add(disabledItem);
        
        dict.setItems(items);
        
        // When
        DictDataResult result = dictVersionService.loadStaticDictData(dict);
        
        // Then
        assertTrue(result.getSuccess());
        assertEquals(2, result.getItems().size());
        
        // 验证禁用项
        DictDataResult.DictItemData disabledLoadedItem = result.getItems().get(1);
        assertEquals("deleted", disabledLoadedItem.getValue());
        assertFalse(disabledLoadedItem.getEnabled()); // 应该是disabled
    }
    
    // ==================== DYNAMIC字典加载测试 ====================
    
    @Test
    @DisplayName("测试DYNAMIC字典从ab_dict_item表加载成功")
    void testLoadDynamicDictSuccess() {
        // Given - DYNAMIC字典
        Dict dict = createDynamicDict("department", "部门");
        dict.setId(100L);
        
        // 模拟ab_dict_item表中的数据
        List<DictItem> dictItems = new ArrayList<>();
        dictItems.add(createDictItem(100L, "dept001", "研发部", 1));
        dictItems.add(createDictItem(100L, "dept002", "市场部", 2));
        dictItems.add(createDictItem(100L, "dept003", "财务部", 3));
        
        when(dictItemMapper.findByDictId(dict.getId()))
            .thenReturn(dictItems);
        
        // When
        DictDataResult result = dictVersionService.loadDynamicDictData(dict);
        
        // Then
        assertNotNull(result);
        assertTrue(result.getSuccess());
        assertEquals("department", result.getCode());
        assertEquals("部门", result.getName());
        assertEquals("dynamic", result.getDictType());
        
        // 验证items加载正确
        assertNotNull(result.getItems());
        assertEquals(3, result.getItems().size());
        
        // 验证第一个item
        DictDataResult.DictItemData firstItem = result.getItems().get(0);
        assertEquals("dept001", firstItem.getValue());
        assertEquals("研发部", firstItem.getLabel());
        assertEquals(1, firstItem.getSortOrder());
        assertTrue(firstItem.getEnabled());
        
        // 验证itemMap
        assertNotNull(result.getItemMap());
        assertEquals(3, result.getItemMap().size());
        assertEquals("研发部", result.getItemMap().get("dept001"));
        
        verify(dictItemMapper).findByDictId(dict.getId());
    }
    
    @Test
    @DisplayName("测试DYNAMIC字典无数据")
    void testLoadDynamicDictNoData() {
        // Given
        Dict dict = createDynamicDict("empty_dict", "空字典");
        dict.setId(100L);
        
        when(dictItemMapper.findByDictId(dict.getId()))
            .thenReturn(new ArrayList<>());
        
        // When
        DictDataResult result = dictVersionService.loadDynamicDictData(dict);
        
        // Then
        assertTrue(result.getSuccess());
        assertNotNull(result.getItems());
        assertEquals(0, result.getItems().size());
        assertNotNull(result.getItemMap());
        assertEquals(0, result.getItemMap().size());
    }
    
    @Test
    @DisplayName("测试DYNAMIC字典加载异常")
    void testLoadDynamicDictException() {
        // Given
        Dict dict = createDynamicDict("error_dict", "错误字典");
        dict.setId(100L);
        
        when(dictItemMapper.findByDictId(dict.getId()))
            .thenThrow(new RuntimeException("数据库连接失败"));
        
        // When
        DictDataResult result = dictVersionService.loadDynamicDictData(dict);
        
        // Then
        assertNotNull(result);
        assertFalse(result.getSuccess());
        assertTrue(result.getErrorMessage().contains("加载失败"));
    }
    
    // ==================== 统一加载接口测试 ====================
    
    @Test
    @DisplayName("测试统一加载接口 - STATIC字典")
    void testLoadUnifiedDictDataForStatic() {
        // Given - STATIC dict now loads from ab_dict_item table (same as DYNAMIC)
        Dict dict = createStaticDict("user_status", "用户状态");
        dict.setId(200L);
        
        // Mock ab_dict_item table data for STATIC dict
        List<DictItem> dictItems = new ArrayList<>();
        dictItems.add(createDictItem(200L, "active", "激活", 1));
        
        when(dictItemMapper.findByDictId(dict.getId()))
            .thenReturn(dictItems);
        
        // When
        DictDataResult result = dictVersionService.loadUnifiedDictData(dict);
        
        // Then
        assertTrue(result.getSuccess());
        assertEquals("static", result.getDictType());
        assertEquals(1, result.getItems().size());
    }
    
    @Test
    @DisplayName("测试统一加载接口 - DYNAMIC字典")
    void testLoadUnifiedDictDataForDynamic() {
        // Given
        Dict dict = createDynamicDict("department", "部门");
        dict.setId(100L);
        
        List<DictItem> dictItems = new ArrayList<>();
        dictItems.add(createDictItem(100L, "dept001", "研发部", 1));
        
        when(dictItemMapper.findByDictId(dict.getId()))
            .thenReturn(dictItems);
        
        // When
        DictDataResult result = dictVersionService.loadUnifiedDictData(dict);
        
        // Then
        assertTrue(result.getSuccess());
        assertEquals("dynamic", result.getDictType());
        assertEquals(1, result.getItems().size());
    }
    
    @Test
    @DisplayName("测试统一加载接口 - 不支持的字典类型")
    void testLoadUnifiedDictDataUnsupportedType() {
        // Given - UNKNOWN type will be treated as DYNAMIC and load from dict_item table
        Dict dict = new Dict();
        dict.setId(300L);
        dict.setCode("unknown");
        dict.setName("未知类型");
        dict.setDictType("unknown");
        
        // Mock empty dict_item table data
        when(dictItemMapper.findByDictId(dict.getId()))
            .thenReturn(new ArrayList<>());
        
        // When
        DictDataResult result = dictVersionService.loadUnifiedDictData(dict);
        
        // Then - UNKNOWN type is treated as DYNAMIC and returns success with empty items
        assertTrue(result.getSuccess());
        assertEquals("unknown", result.getDictType());
        assertEquals(0, result.getItems().size());
    }
    
    // ==================== 辅助方法 ====================
    
    /**
     * 创建STATIC字典
     */
    private Dict createStaticDict(String code, String name) {
        Dict dict = new Dict();
        dict.setId(1L);
        dict.setPid("dict_" + code);
        dict.setTenantId(1L);

        dict.setCode(code);
        dict.setName(name);
        dict.setDictType("static");
        dict.setStatus("enabled");
        dict.setVersion(1);
        dict.setSemver("1.0.0");
        dict.setCreatedAt(Instant.now());
        dict.setUpdatedAt(Instant.now());
        return dict;
    }
    
    /**
     * 创建DYNAMIC字典
     */
    private Dict createDynamicDict(String code, String name) {
        Dict dict = new Dict();
        dict.setId(1L);
        dict.setPid("dict_" + code);
        dict.setTenantId(1L);

        dict.setCode(code);
        dict.setName(name);
        dict.setDictType("dynamic");
        dict.setStatus("enabled");
        dict.setVersion(1);
        dict.setSemver("1.0.0");
        dict.setCreatedAt(Instant.now());
        dict.setUpdatedAt(Instant.now());
        return dict;
    }
    
    /**
     * 创建DataSourceItemBean (用于STATIC字典的items)
     */
    private DataSourceItemBean createDataSourceItem(String value, String label, Integer order) {
        DataSourceItemBean item = new DataSourceItemBean();
        item.setValue(value);
        item.setLabel(label);
        item.setOrder(order);
        item.setDisabled(false);
        return item;
    }
    
    /**
     * 创建DictItem (用于DYNAMIC字典的ab_dict_item表数据)
     */
    private DictItem createDictItem(Long dictId, String value, String label, Integer sortNo) {
        DictItem item = new DictItem();
        item.setId(System.currentTimeMillis());
        item.setDictId(dictId);
        item.setValue(value);
        item.setLabel(label);
        item.setSortNo(sortNo);
        item.setStatus("enabled");
        item.setCreatedAt(Instant.now());
        item.setUpdatedAt(Instant.now());
        return item;
    }
}
