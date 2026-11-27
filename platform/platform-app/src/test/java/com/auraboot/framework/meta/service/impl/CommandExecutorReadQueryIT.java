package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.constant.Status;
import com.auraboot.framework.meta.dto.CommandDefinitionCreateRequest;
import com.auraboot.framework.meta.dto.CommandDefinitionDTO;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.CommandExecuteResult;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.entity.payload.FieldFeatureBean;
import com.auraboot.framework.meta.exception.MetaRecordNotFoundException;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.CommandExecutor;
import com.auraboot.framework.meta.service.CommandService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-stack regression for a command of type query. Such a command declares no handler, so
 * nothing of its own read for it: executing it completed with an empty object (57 CRM query
 * commands answered nothing). The executor now reads the model itself, strictly: a parameter
 * that is not part of the query contract is refused. A query that declares input fields used to
 * take the field-map path whose fall-through is INSERT, which saved the "filter" as a record.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CommandExecutorReadQueryIT extends BaseIntegrationTest {

    @Autowired private MetaModelService metaModelService;
    @Autowired private MetaModelMapper metaModelMapper;
    @Autowired private MetaFieldMapper metaFieldMapper;
    @Autowired private MetaModelFieldBindingMapper bindingMapper;
    @Autowired private DynamicDataMapper dynamicDataMapper;
    @Autowired private CommandService commandService;
    @Autowired private CommandExecutor commandExecutor;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);
    private final String model = "erq_m_" + suffix;
    private final String table = "mt_" + model;
    private final String listCmd = "erqtest:list_" + suffix;
    private final String detailCmd = "erqtest:detail_" + suffix;
    private final String filteredCmd = "erqtest:filtered_" + suffix;
    private final List<String> names = List.of("north depot", "north harbor", "south depot");
    private final List<String> pids = new ArrayList<>();

    @BeforeAll
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void setUp() throws Exception {
        super.setupTenantContext();
        MetaContext.setContext(getTestTenant().getId(), getTestUser().getId(), getTestUser().getPid(), getTestUser().getUserName());

        dropIfExists(table);
        cleanMeta();
        cleanCommands();

        Model m = buildModel(model, "ERQ Depot");
        metaModelMapper.insert(m);
        Field name = buildField("m_name");
        metaFieldMapper.insert(name);
        bindingMapper.insert(buildBinding(m.getId(), name.getId(), 1));
        metaModelService.publish(m.getPid(), model + " IT fixture");

        createQueryCommand(listCmd, null);
        createQueryCommand(detailCmd, null);
        createQueryCommand(filteredCmd, List.of("m_name"));

        for (String depot : names) {
            String pid = UniqueIdGenerator.generate();
            jdbcTemplate.update("INSERT INTO " + table + " (pid, tenant_id, m_name) VALUES (?, ?, ?)",
                    pid, getTestTenant().getId(), depot);
            pids.add(pid);
        }
    }

    @AfterAll
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void tearDown() {
        try {
            dropIfExists(table);
            cleanMeta();
            cleanCommands();
        } catch (Exception ignored) {}
    }

    @BeforeEach
    void ctx() {
        MetaContext.setContext(getTestTenant().getId(), getTestUser().getId(), getTestUser().getPid(), getTestUser().getUserName());
        MetaContext.setMemberId(getTestTenantMember().getId());
        grantCommittedPermissionToTestRole("model." + model + ".read", "model", model, "read",
                "Read the ERQ model");
    }

    @Test
    void aListQueryAnswersTheRecordsOfItsModel() {
        CommandExecuteResult result = commandExecutor.execute(listCmd, request(Map.of()));

        assertEquals(3L, ((Number) result.getData().get("total")).longValue(), "data=" + result.getData());
        assertEquals(new HashSet<>(pids), recordPids(result), "data=" + result.getData());
        log.info("[executor read query] PASS — list answered {} records", recordPids(result).size());
    }

    @Test
    void aFilterNarrowsTheListAndPagingBoundsThePage() {
        CommandExecuteResult filtered = commandExecutor.execute(listCmd, request(Map.of("filters",
                List.of(Map.of("fieldName", "m_name", "operator", "EQ", "value", "north harbor")))));
        assertEquals(1L, ((Number) filtered.getData().get("total")).longValue(), "data=" + filtered.getData());
        assertEquals(Set.of(pids.get(1)), recordPids(filtered), "data=" + filtered.getData());

        CommandExecuteResult page = commandExecutor.execute(listCmd, request(Map.of("pageSize", 2, "pageNum", 2)));
        assertEquals(3L, ((Number) page.getData().get("total")).longValue(), "data=" + page.getData());
        assertEquals(1, ((List<?>) page.getData().get("records")).size(), "page 2 of a 3-row model holds one record");
    }

    @Test
    void aDetailQueryAnswersTheRecordTheRequestNames() {
        CommandExecuteResult byTarget = commandExecutor.execute(detailCmd, namedRequest(pids.get(1), Map.of()));
        Map<?, ?> record = (Map<?, ?>) byTarget.getData().get("record");
        assertEquals(pids.get(1), record.get("pid"), "data=" + byTarget.getData());
        assertEquals("north harbor", record.get("m_name"));

        CommandExecuteResult throughPayload = commandExecutor.execute(detailCmd, request(Map.of("recordPid", pids.get(0))));
        assertEquals(pids.get(0), ((Map<?, ?>) throughPayload.getData().get("record")).get("pid"),
                "data=" + throughPayload.getData());
    }

    @Test
    void aDetailQueryNamingAnAbsentRecordIsNotFound() {
        CommandExecuteRequest byTarget = new CommandExecuteRequest();
        byTarget.setTargetRecordId("erq-absent-" + suffix);
        CommandExecuteRequest byPayload = request(Map.of("recordPid", "erq-absent-" + suffix));

        for (CommandExecuteRequest naming : List.of(byTarget, byPayload)) {
            MetaRecordNotFoundException missing = assertThrows(MetaRecordNotFoundException.class,
                    () -> commandExecutor.execute(detailCmd, naming));
            assertNotNull(missing.getMessage());
        }
    }

    @Test
    void aFilterTheExecutorCannotReadIsRefusedAsABadRequest() {
        CommandExecuteRequest unreadable = request(Map.of("filters", "not a list"));

        BusinessException refused = assertThrows(BusinessException.class, () -> commandExecutor.execute(listCmd, unreadable));

        assertEquals(ResponseCode.BadParam, refused.getResponseCode());
        assertTrue(refused.getMessage().contains("filters"), refused.getMessage());
    }

    @Test
    void aParameterOutsideTheQueryContractIsRefusedAndNothingIsWritten() {
        CommandExecuteRequest notAParameter = request(Map.of("m_name", "north depot"));

        BusinessException refused = null;
        try {
            commandExecutor.execute(filteredCmd, notAParameter);
        } catch (BusinessException e) {
            refused = e;
        }
        // Counted first: before the refusal is examined, the model must still hold exactly its three rows.
        assertEquals(3L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class),
                "the filter was not saved as a record");
        assertNotNull(refused, "a parameter that is not part of the query contract is refused");
        assertEquals(ResponseCode.BadParam, refused.getResponseCode());
        assertTrue(refused.getMessage().contains("m_name"), refused.getMessage());
    }

    // ------------------------------------------------------------------ fixture helpers

    @SuppressWarnings("unchecked")
    private Set<String> recordPids(CommandExecuteResult result) {
        Set<String> pids = new HashSet<>();
        for (Map<String, Object> record : (List<Map<String, Object>>) result.getData().get("records")) {
            pids.add(String.valueOf(record.get("pid")));
        }
        return pids;
    }

    private CommandExecuteRequest request(Map<String, Object> payload) {
        CommandExecuteRequest request = new CommandExecuteRequest();
        request.setPayload(new LinkedHashMap<>(payload));
        return request;
    }

    private CommandExecuteRequest namedRequest(String recordId, Map<String, Object> payload) {
        CommandExecuteRequest request = request(payload);
        request.setTargetRecordId(recordId);
        return request;
    }

    private void createQueryCommand(String code, List<String> inputFields) throws Exception {
        Map<String, Object> execConfig = new LinkedHashMap<>();
        execConfig.put("type", "query");
        execConfig.put("modelCode", model);
        if (inputFields != null) execConfig.put("inputFields", inputFields);
        CommandDefinitionCreateRequest req = new CommandDefinitionCreateRequest();
        req.setCode(code);
        req.setDisplayName(code);
        req.setModelCode(model);
        req.setExecutionConfig(objectMapper.writeValueAsString(execConfig));
        req.setCmdRiskLevel("L0");
        CommandDefinitionDTO dto = commandService.create(req);
        commandService.publish(dto.getPid());
    }

    private void cleanCommands() {
        for (String code : List.of(listCmd, detailCmd, filteredCmd)) {
            try {
                jdbcTemplate.update("DELETE FROM ab_command_definition WHERE code = ?", code);
            } catch (Exception ignored) {}
        }
    }

    private void cleanMeta() {
        try {
            jdbcTemplate.update(
                    "DELETE FROM ab_meta_field WHERE id IN (SELECT field_id FROM ab_meta_model_field_binding b "
                            + "JOIN ab_meta_model m ON m.id = b.model_id WHERE m.code = ?)", model);
            jdbcTemplate.update("DELETE FROM ab_meta_model_field_binding WHERE model_id IN "
                    + "(SELECT id FROM ab_meta_model WHERE code = ?)", model);
            jdbcTemplate.update("DELETE FROM ab_meta_model WHERE code = ?", model);
        } catch (Exception e) {
            log.warn("[cleanup] meta cleanup for {} failed: {}", model, e.getMessage());
        }
    }

    private void dropIfExists(String tableName) {
        try {
            dynamicDataMapper.alterTable("DROP TABLE IF EXISTS " + tableName);
        } catch (Exception e) {
            log.debug("[setup] drop {} skipped: {}", tableName, e.getMessage());
        }
    }

    private Model buildModel(String code, String displayName) {
        Model m = new Model();
        m.setPid(UniqueIdGenerator.generate());
        m.setTenantId(getTestTenant().getId());
        m.setCode(code);
        m.setVersion(1);
        m.setIsCurrent(true);
        m.setStatus(Status.DRAFT.getCode());
        m.setCreatedAt(Instant.now());
        m.setUpdatedAt(Instant.now());
        m.setDeletedFlag(false);
        ExtensionBean ext = new ExtensionBean();
        Map<String, Object> extMap = new HashMap<>();
        extMap.put("displayName", displayName);
        extMap.put("description", displayName + " — IT fixture");
        extMap.put("modelType", "entity");
        ext.setExtension(extMap);
        m.setExtension(ext);
        return m;
    }

    private Field buildField(String code) {
        Field f = new Field();
        f.setPid(UniqueIdGenerator.generate());
        f.setTenantId(getTestTenant().getId());
        f.setCode(code);
        f.setDataType("string");
        f.setVersion(1);
        f.setIsCurrent(true);
        f.setStatus(Status.PUBLISHED.getCode());
        f.setCreatedAt(Instant.now());
        f.setUpdatedAt(Instant.now());
        f.setDeletedFlag(false);
        FieldFeatureBean feature = new FieldFeatureBean();
        feature.setRequired(false);
        feature.setUnique(false);
        f.setFeature(feature);
        ExtensionBean ext = new ExtensionBean();
        Map<String, Object> extMap = new HashMap<>();
        extMap.put("displayName", code);
        f.setExtension(ext);
        return f;
    }

    private ModelFieldBinding buildBinding(Long modelId, Long fieldId, int order) {
        ModelFieldBinding b = new ModelFieldBinding();
        b.setTenantId(getTestTenant().getId());
        b.setModelId(modelId);
        b.setFieldId(fieldId);
        b.setFieldOrder(order);
        return b;
    }
}
