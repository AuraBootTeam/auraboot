package com.auraboot.framework.meta.controller;

import com.auraboot.smart.framework.engine.common.util.CollectionUtil;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.currency.service.ReportingCurrencyConverter;
import com.auraboot.framework.exception.DataNotFoundException;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.entity.payload.DataSourceItemBean;
import com.auraboot.framework.meta.service.DictService;
import com.auraboot.framework.meta.service.NamedQueryService;
import com.auraboot.framework.currency.spi.CurrencyConversionSpi;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数据源控制器
 * 提供统一的数据源查询接口
 *
 * @author AuraBoot Team
 * @since 1.0.0
 */
@Slf4j
@RestController
@RequestMapping("/api/datasource")
@Tag(name = "数据源管理", description = "数据源查询和管理接口")
public class DataSourceController {

    @Autowired
    private DictService dictService;

    @Autowired
    private NamedQueryService namedQueryService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired(required = false)
    private ReportingCurrencyConverter reportingCurrencyConverter;

    @Autowired(required = false)
    private CurrencyConversionSpi currencyConversionService;

    /**
     * 通用数据源列表查询
     * 默认endpoint，支持通过params指定具体的数据源
     * 
     * 兼容性说明：此接口已迁移到使用字典服务，但保持原有API格式不变
     */
    @GetMapping("/list")
    @Operation(summary = "查询数据源列表", description = "通用数据源查询接口。支持 nq:{queryCode} 格式使用NamedQuery作为数据源。format=records 返回原始行数据。reportingCurrency 指定报告货币（如 USD），结果中 _base 字段将新增对应 _reporting 字段。")
    @RequirePermission(MetaPermission.DATASOURCE_READ)
    public ApiResponse<?> list(
            @Parameter(description = "数据源Key。支持字典编码或 nq:{queryCode} 格式的NamedQuery") @RequestParam() String datasourceId,
            @Parameter(description = "报告货币代码（如 USD）。存在时对结果集 _base 字段进行货币转换，新增 _reporting 后缀字段") @RequestParam(required = false) String reportingCurrency,
            @RequestParam Map<String, Object> params) {
        log.info("查询数据源列表: dsKey={}, params={}", datasourceId, params);

        // NamedQuery data source: nq:{queryCode} format
        if (datasourceId.startsWith("nq:")) {
            String queryCode = datasourceId.substring(3);
            String format = params.containsKey("format") ? params.get("format").toString() : "options";
            if ("records".equals(format)) {
                PaginationResult<Map<String, Object>> result = loadNamedQueryRecords(queryCode, params);
                applyReportingCurrency(result.getRecords(), reportingCurrency);
                return ApiResponse.success(result);
            }
            return ApiResponse.success(loadNamedQueryOptions(queryCode, params));
        }

        // 通过字典服务查询数据源
        DictDTO dictDTO = dictService.findByCode(datasourceId);
        if (dictDTO == null) {
            throw new DataNotFoundException(ResponseCode.BadParam, "Datasource not found : " + datasourceId);
        }

        // 转换为兼容的DataSourceResponse格式
        DataSourceResponse dataSourceResponse = convertDictToDataSourceResponse(dictDTO);

        return ApiResponse.success(dataSourceResponse.getItems());
    }

    /**
     * 将DictDTO转换为DataSourceResponse格式以保持API兼容性
     */
    private DataSourceResponse convertDictToDataSourceResponse(DictDTO dictDTO) {
        DataSourceResponse response = new DataSourceResponse();
        response.setCode(dictDTO.getCode());
        response.setType(dictDTO.getDictType());
        
        // 从字典的items字段中解析数据（迁移后的数据存储在这里）
        List<DataSourceItemBean> items = new ArrayList<>();
        
        try {
            // 首先尝试从items字段获取数据（迁移后的主要数据源）
             if (!CollectionUtil.isEmpty(dictDTO.getItems())) {
                 items = dictDTO.getItems();
            } else {
                // 降级方案：从字典项中获取数据
                log.info("字典 {} 的items和sourceConfig字段都为空，尝试从字典项获取数据", dictDTO.getCode());
                items = loadItemsFromDictService(dictDTO);
            }
        } catch (Exception e) {
            log.error("解析字典items失败: code={}", dictDTO.getCode(), e);
            // 降级到从字典项获取数据
            items = loadItemsFromDictService(dictDTO);
        }
        
        response.setItems(items);
        return response;
    }

    /**
     * 将JsonNode转换为DataSourceItemBean
     */
    private DataSourceItemBean convertJsonNodeToDataSourceItem(JsonNode itemNode) {
        DataSourceItemBean item = new DataSourceItemBean();
        
        if (itemNode.has("code")) {
            item.setCode(itemNode.get("code").asText());
        }
        if (itemNode.has("key")) {
            item.setKey(itemNode.get("key").asText());
        }
        if (itemNode.has("name")) {
            item.setName(itemNode.get("name").asText());
        }
        if (itemNode.has("label")) {
            item.setLabel(itemNode.get("label").asText());
        }
        if (itemNode.has("value")) {
            JsonNode valueNode = itemNode.get("value");
            if (valueNode.isTextual()) {
                item.setValue(valueNode.asText());
            } else {
                item.setValue(valueNode);
            }
        }
        if (itemNode.has("description")) {
            item.setDescription(itemNode.get("description").asText());
        }
        if (itemNode.has("disabled")) {
            item.setDisabled(itemNode.get("disabled").asBoolean());
        }
        if (itemNode.has("icon")) {
            item.setIcon(itemNode.get("icon").asText());
        }
        if (itemNode.has("group")) {
            item.setGroup(itemNode.get("group").asText());
        }
        if (itemNode.has("order")) {
            item.setOrder(itemNode.get("order").asInt());
        }
        
        // 处理扩展属性
        if (itemNode.has("extra")) {
            try {
                item.setExtra(objectMapper.convertValue(itemNode.get("extra"), Map.class));
            } catch (Exception e) {
                log.warn("解析extra属性失败", e);
            }
        }
        
        return item;
    }

    /**
     * 从字典服务加载字典项数据（当sourceConfig不可用时的降级方案）
     */
    private List<DataSourceItemBean> loadItemsFromDictService(DictDTO dictDTO) {
        List<DataSourceItemBean> items = new ArrayList<>();
        
        try {
            // 如果是级联字典，获取根级项
            if ("cascade".equals(dictDTO.getDictType())) {
                List<DictItemData> dictItems = dictService.getCascadeChildren(dictDTO.getPid(), null);
                for (DictItemData dictItem : dictItems) {
                    items.add(convertDictItemToDataSourceItem(dictItem));
                }
            } else {
                // 对于普通字典，这里需要实现获取字典项的逻辑
                // 由于DictService接口中没有直接获取字典项的方法，我们可能需要扩展
                log.warn("普通字典 {} 无法从字典项获取数据，需要扩展DictService接口", dictDTO.getCode());
            }
        } catch (Exception e) {
            log.error("从字典服务加载数据失败: code={}", dictDTO.getCode(), e);
        }
        
        return items;
    }

    /**
     * Load raw records from a NamedQuery (format=records).
     * Returns PaginationResult with full row data, suitable for table/list display.
     */
    private PaginationResult<Map<String, Object>> loadNamedQueryRecords(String queryCode, Map<String, Object> params) {
        int maxItems = 200;
        try {
            if (params.containsKey("maxItems")) {
                maxItems = Integer.parseInt(params.get("maxItems").toString());
            }
        } catch (NumberFormatException ignored) {}

        NamedQueryTestRequest nqRequest = new NamedQueryTestRequest();
        nqRequest.setPage(1);
        nqRequest.setSize(Math.min(maxItems, 1000));
        nqRequest.setExecuteQuery(true);
        nqRequest.setParameters(buildNqParams(params));

        return namedQueryService.executeQuery(queryCode, nqRequest);
    }

    /**
     * Load options from a NamedQuery.
     * Executes the query and converts each row to a DataSourceItemBean.
     *
     * Supported params:
     *   valueField (default: "id")   — column to use as value
     *   labelField (default: "name") — column to use as label
     *   searchField — optional column for keyword filtering
     *   keyword — search term (applied to searchField)
     *   maxItems (default: 200) — max number of items
     */
    private List<DataSourceItemBean> loadNamedQueryOptions(String queryCode, Map<String, Object> params) {
        String valueField = params.getOrDefault("valueField", "id").toString();
        String labelField = params.getOrDefault("labelField", "name").toString();
        String searchField = params.containsKey("searchField") ? params.get("searchField").toString() : null;
        String keyword = params.containsKey("keyword") ? params.get("keyword").toString() : null;
        int maxItems = 200;
        try {
            if (params.containsKey("maxItems")) {
                maxItems = Integer.parseInt(params.get("maxItems").toString());
            }
        } catch (NumberFormatException ignored) {}

        NamedQueryTestRequest nqRequest = new NamedQueryTestRequest();
        nqRequest.setPage(1);
        nqRequest.setSize(Math.min(maxItems, 1000));
        nqRequest.setExecuteQuery(true);
        nqRequest.setParameters(buildNqParams(params));

        // Apply keyword search if provided
        if (keyword != null && !keyword.isBlank() && searchField != null) {
            var mapper = new ObjectMapper();
            var whereArray = mapper.createArrayNode();
            var node = mapper.createObjectNode();
            node.put("field", searchField);
            node.put("operator", "like");
            node.put("value", keyword);
            whereArray.add(node);
            nqRequest.setWhereConditions(whereArray);
        }

        PaginationResult<Map<String, Object>> result = namedQueryService.executeQuery(queryCode, nqRequest);

        List<DataSourceItemBean> items = new ArrayList<>();
        if (result.getRecords() != null) {
            int order = 0;
            for (Map<String, Object> row : result.getRecords()) {
                DataSourceItemBean item = new DataSourceItemBean();
                Object val = row.get(valueField);
                Object lbl = row.get(labelField);
                item.setValue(val != null ? val : "");
                item.setLabel(lbl != null ? lbl.toString() : (val != null ? val.toString() : ""));
                item.setKey(val != null ? val.toString() : "");
                item.setCode(val != null ? val.toString() : "");
                item.setName(item.getLabel());
                item.setOrder(order++);
                items.add(item);
            }
        }
        return items;
    }

    private static final Set<String> NQ_CONTROL_PARAMS = Set.of(
            "datasourceId", "format", "maxItems", "valueField", "labelField",
            "searchField", "keyword", "page", "size", "reportingCurrency");

    /**
     * Extract business parameters from HTTP query params, excluding control params.
     */
    private Map<String, Object> buildNqParams(Map<String, Object> params) {
        Map<String, Object> nqParams = new HashMap<>();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (!NQ_CONTROL_PARAMS.contains(entry.getKey())) {
                nqParams.put(entry.getKey(), entry.getValue());
            }
        }
        return nqParams;
    }

    /**
     * Applies reporting-currency conversion to a list of result rows when
     * {@code reportingCurrency} is specified and the required beans are available.
     * No-op if {@code reportingCurrency} is null/blank or the converter is not wired.
     */
    private void applyReportingCurrency(List<Map<String, Object>> rows, String reportingCurrency) {
        if (reportingCurrency == null || reportingCurrency.isBlank()) {
            return;
        }
        if (reportingCurrencyConverter == null || currencyConversionService == null) {
            log.warn("ReportingCurrencyConverter or CurrencyConversionService not available; skipping conversion");
            return;
        }
        String baseCurrency = currencyConversionService.getBaseCurrency();
        reportingCurrencyConverter.convert(rows, reportingCurrency, baseCurrency);
    }

    /**
     * 将DictItemData转换为DataSourceItemBean
     */
    private DataSourceItemBean convertDictItemToDataSourceItem(DictItemData dictItem) {
        DataSourceItemBean item = new DataSourceItemBean();
        
        item.setCode(dictItem.getValue());
        item.setKey(dictItem.getValue());
        item.setValue(dictItem.getValue());
        item.setLabel(dictItem.getLabel());
        item.setName(dictItem.getLabel());
        item.setDescription(dictItem.getDescription());
        item.setDisabled(!dictItem.getEnabled());
        item.setOrder(dictItem.getSortOrder());
        
        // 处理扩展属性
        if (dictItem.getExtension() != null) {
            try {
                if (dictItem.getExtension() instanceof Map) {
                    item.setExtra((Map<String, Object>) dictItem.getExtension());
                } else {
                    Map<String, Object> extra = objectMapper.convertValue(dictItem.getExtension(), Map.class);
                    item.setExtra(extra);
                    
                    // 从扩展属性中提取常用字段
                    if (extra.containsKey("icon")) {
                        item.setIcon((String) extra.get("icon"));
                    }
                    if (extra.containsKey("group")) {
                        item.setGroup((String) extra.get("group"));
                    }
                    if (extra.containsKey("color")) {
                        // color可以作为扩展属性保留
                    }
                }
            } catch (Exception e) {
                log.warn("解析字典项扩展属性失败", e);
            }
        }
        
        return item;
    }





}
