package com.auraboot.framework.scheduler;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.CommandExecuteResult;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.mapper.CommandDefinitionMapper;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.CommandExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the three scheduled_task command-pipeline hardenings:
 *
 * <ul>
 *   <li>G-7 — invalid cron expressions are rejected at command time, not at
 *       scheduler tick time.</li>
 *   <li>G-8 — duplicate (tenant_id, name) creation is blocked with a
 *       business-level error message (the schema.sql unique index is the
 *       last-line defence).</li>
 *   <li>G-9 — {@code admin:delete_scheduled_task} actually removes the row
 *       from {@code ab_scheduled_task} so the list view stops returning it,
 *       even when the request omits an explicit
 *       {@code operationType="delete"} hint.</li>
 * </ul>
 *
 * <p>These tests exercise the platform pipeline end-to-end via
 * {@link CommandExecutor}, which is the same entry point that
 * {@code POST /api/meta/commands/execute/{commandCode}} hits in production.
 */
@DisplayName("Scheduled Task Command Hardening: G-7 / G-8 / G-9")
class ScheduledTaskCommandHardeningIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private CommandExecutor commandExecutor;

    @Autowired
    private DynamicDataMapper dynamicDataMapper;

    @Autowired
    private CommandDefinitionMapper commandDefinitionMapper;

    @Autowired
    private MetaModelMapper metaModelMapper;

    @Autowired
    private MetaFieldMapper metaFieldMapper;

    @Autowired
    private MetaModelFieldBindingMapper fieldBindingMapper;

    @Autowired
    private MetaModelService metaModelService;

    private static final String CREATE_CMD = "admin:create_scheduled_task";
    private static final String DELETE_CMD = "admin:delete_scheduled_task";

    @BeforeEach
    void ensureScheduledTaskCommands() {
        ensureScheduledTaskModel();
        ensureScheduledTaskFields();
        ensureCommand(CREATE_CMD,
                "{\"type\":\"create\",\"inputFields\":[\"name\",\"description\",\"task_type\",\"cron_expression\",\"interval_ms\",\"handler_bean\",\"handler_method\",\"params\",\"max_retries\",\"timeout_ms\",\"enabled\"]}");
        ensureCommand(DELETE_CMD, "{\"type\":\"delete\"}");
    }

    /** G-7: garbage cron expressions must blow up at command time. */
    @Test
    @DisplayName("G-7: invalid cron expression is rejected with non-success result")
    void invalidCronExpressionIsRejected() {
        CommandExecuteRequest request = new CommandExecuteRequest();
        Map<String, Object> payload = new HashMap<>();
        payload.put("name", "g7-bad-cron-" + System.nanoTime());
        payload.put("task_type", "cron");
        payload.put("cron_expression", "every-minute-please");
        payload.put("handler_bean", "noopHandler");
        payload.put("enabled", false);
        request.setPayload(payload);
        request.setOperationType("create");

        Throwable thrown = assertThrows(Throwable.class,
                () -> commandExecutor.execute(CREATE_CMD, request));

        // Either ValidationException (preferred) or BusinessException wrapping
        // it — both surface as a non-zero response code at the controller.
        assertTrue(thrown instanceof ValidationException
                        || thrown instanceof BusinessException
                        || (thrown.getCause() != null
                                && (thrown.getCause() instanceof ValidationException
                                        || thrown.getCause() instanceof BusinessException)),
                () -> "Expected validation/business exception, got " + thrown);
        assertTrue(thrown.getMessage() != null
                        && thrown.getMessage().toLowerCase().contains("cron"),
                () -> "Expected message to mention cron, got: " + thrown.getMessage());
    }

    /** G-8: creating a second task with the same name returns a non-success result. */
    @Test
    @DisplayName("G-8: duplicate scheduled task name is rejected on second create")
    void duplicateScheduledTaskNameIsRejected() {
        String name = "g8-dup-" + System.nanoTime();

        CommandExecuteRequest first = new CommandExecuteRequest();
        Map<String, Object> firstPayload = new HashMap<>();
        firstPayload.put("name", name);
        firstPayload.put("task_type", "interval");
        firstPayload.put("interval_ms", 60000L);
        firstPayload.put("handler_bean", "noopHandler");
        firstPayload.put("enabled", false);
        first.setPayload(firstPayload);
        first.setOperationType("create");

        CommandExecuteResult firstResult = commandExecutor.execute(CREATE_CMD, first);
        assertNotNull(firstResult, "first create should succeed");

        // Second create with the same name must blow up.
        CommandExecuteRequest second = new CommandExecuteRequest();
        Map<String, Object> secondPayload = new HashMap<>(firstPayload);
        second.setPayload(secondPayload);
        second.setOperationType("create");

        Throwable thrown = assertThrows(Throwable.class,
                () -> commandExecutor.execute(CREATE_CMD, second));
        assertTrue(thrown instanceof ValidationException
                        || thrown instanceof BusinessException
                        || (thrown.getCause() != null
                                && (thrown.getCause() instanceof ValidationException
                                        || thrown.getCause() instanceof BusinessException)),
                () -> "Expected validation/business exception, got " + thrown);
        assertTrue(thrown.getMessage() != null
                        && thrown.getMessage().toLowerCase().contains("name"),
                () -> "Expected message to mention name, got: " + thrown.getMessage());
    }

    /** G-9: delete command actually removes the row so the list stops returning it. */
    @Test
    @DisplayName("G-9: delete_scheduled_task removes the row from ab_scheduled_task")
    void deleteScheduledTaskRemovesRow() {
        String name = "g9-deleteme-" + System.nanoTime();

        CommandExecuteRequest createReq = new CommandExecuteRequest();
        Map<String, Object> createPayload = new HashMap<>();
        createPayload.put("name", name);
        createPayload.put("task_type", "interval");
        createPayload.put("interval_ms", 60000L);
        createPayload.put("handler_bean", "noopHandler");
        createPayload.put("enabled", false);
        createReq.setPayload(createPayload);
        createReq.setOperationType("create");

        commandExecutor.execute(CREATE_CMD, createReq);

        // Locate the just-created row's pid via the dynamic mapper (same path
        // the list endpoint uses).
        String selectSql = "SELECT pid FROM ab_scheduled_task WHERE name = #{params.name}";
        Map<String, Object> selectParams = new HashMap<>();
        selectParams.put("name", name);
        List<Map<String, Object>> rowsBefore = dynamicDataMapper.selectByQuery(selectSql, selectParams);
        assertEquals(1, rowsBefore.size(), "row should exist before delete");
        String pid = (String) rowsBefore.get(0).get("pid");
        assertNotNull(pid);

        // Issue the delete WITHOUT setting operationType="delete" on the
        // request — verifying the FieldMapPhase fix that lets execConfig.type
        // alone drive the implicit delete branch.
        CommandExecuteRequest deleteReq = new CommandExecuteRequest();
        deleteReq.setPayload(new HashMap<>());
        deleteReq.setTargetRecordId(pid);
        // Intentionally no setOperationType — the platform must derive
        // operationType from execConfig.type for type=delete commands.
        commandExecutor.execute(DELETE_CMD, deleteReq);

        List<Map<String, Object>> rowsAfter = dynamicDataMapper.selectByQuery(selectSql, selectParams);
        assertTrue(rowsAfter.isEmpty(),
                () -> "row should be hard-deleted; still present: " + rowsAfter);
    }

    private void ensureCommand(String code, String executionConfig) {
        if (commandDefinitionMapper.findCurrentByCode(code) != null) {
            return;
        }
        CommandDefinition command = new CommandDefinition();
        command.setPid(UniqueIdGenerator.generate());
        command.setTenantId(getTestTenant().getId());
        command.setCode(code);
        command.setDisplayName(code);
        command.setDescription("Scheduled task command fixture for integration tests");
        command.setModelCode("scheduled_task");
        command.setInputSchema("{}");
        command.setTargetModels("[]");
        command.setExecutionConfig(executionConfig);
        command.setExtension(new ExtensionBean());
        command.setCmdRiskLevel("L1");
        command.setVersion(1);
        command.setIsCurrent(true);
        command.setRowVersion(1);
        command.setStatus("published");
        command.setDeletedFlag(false);
        command.setCreatedAt(Instant.now());
        command.setUpdatedAt(Instant.now());
        commandDefinitionMapper.insertIdempotent(command);
    }

    private void ensureScheduledTaskModel() {
        Model existing = metaModelMapper.findCurrentByCode("scheduled_task");
        if (existing != null && "ab_scheduled_task".equals(existing.getTableName())) {
            return;
        }

        Model model = existing != null ? existing : new Model();
        if (model.getPid() == null) {
            model.setPid(UniqueIdGenerator.generate());
        }
        model.setTenantId(getTestTenant().getId());
        model.setCode("scheduled_task");
        model.setTableName("ab_scheduled_task");
        model.setModelCategory("master");
        model.setSourceType("physical");
        model.setDomainCategory("platform");
        model.setDataSensitivity("internal");
        model.setSemanticDescription("Configurable scheduled task definitions");
        ExtensionBean extension = model.getExtension() != null ? model.getExtension() : new ExtensionBean();
        extension.setDynamicProperty("displayName", "Scheduled Task");
        extension.setDynamicProperty("modelType", "entity");
        extension.setDynamicProperty("titleField", "name");
        extension.setDynamicProperty("softDelete", false);
        model.setExtension(extension);
        model.setVersion(model.getVersion() != null ? model.getVersion() : 1);
        model.setIsCurrent(true);
        model.setRowVersion(model.getRowVersion() != null ? model.getRowVersion() : 1);
        model.setStatus("published");
        model.setDeletedFlag(false);
        Instant now = Instant.now();
        if (model.getCreatedAt() == null) {
            model.setCreatedAt(now);
        }
        model.setUpdatedAt(now);

        if (model.getId() == null) {
            metaModelMapper.insert(model);
        } else {
            metaModelMapper.updateById(model);
        }
        metaModelService.refreshModelCache("scheduled_task");
    }

    private void ensureScheduledTaskFields() {
        Model model = metaModelMapper.findCurrentByCode("scheduled_task");
        assertNotNull(model, "scheduled_task model fixture should exist before binding fields");

        int order = 1;
        ensureFieldBound(model, "name", "string", order++);
        ensureFieldBound(model, "description", "text", order++);
        ensureFieldBound(model, "task_type", "string", order++);
        ensureFieldBound(model, "cron_expression", "string", order++);
        ensureFieldBound(model, "interval_ms", "long", order++);
        ensureFieldBound(model, "handler_bean", "string", order++);
        ensureFieldBound(model, "handler_method", "string", order++);
        ensureFieldBound(model, "params", "jsonb", order++);
        ensureFieldBound(model, "max_retries", "integer", order++);
        ensureFieldBound(model, "timeout_ms", "integer", order++);
        ensureFieldBound(model, "enabled", "boolean", order);
        metaModelService.refreshModelCache("scheduled_task");
    }

    private void ensureFieldBound(Model model, String code, String dataType, int order) {
        Field field = metaFieldMapper.findCurrentByCode(code);
        if (field == null) {
            field = new Field();
            field.setPid(UniqueIdGenerator.generate());
            field.setTenantId(getTestTenant().getId());
            field.setCode(code);
            field.setDataType(dataType);
            field.setVersion(1);
            field.setIsCurrent(true);
            field.setRowVersion(1);
            field.setStatus("published");
            field.setDeletedFlag(false);
            field.setCreatedAt(Instant.now());
            field.setUpdatedAt(Instant.now());

            ExtensionBean extension = new ExtensionBean();
            Map<String, Object> extensionMap = new HashMap<>();
            extensionMap.put("displayName", code);
            extension.setExtension(extensionMap);
            field.setExtension(extension);
            metaFieldMapper.insert(field);
            field = metaFieldMapper.findCurrentByCode(code);
        }
        assertNotNull(field, "scheduled_task field fixture should exist: " + code);

        ModelFieldBinding existing = fieldBindingMapper.selectByModelAndField(model.getId(), field.getId());
        if (existing == null) {
            ModelFieldBinding binding = new ModelFieldBinding();
            binding.setTenantId(getTestTenant().getId());
            binding.setModelId(model.getId());
            binding.setFieldId(field.getId());
            binding.setFieldOrder(order);
            binding.setRequired(false);
            binding.setVisible(true);
            binding.setEditable(true);
            binding.setCreatedAt(Instant.now());
            binding.setUpdatedAt(Instant.now());
            fieldBindingMapper.insert(binding);
        } else if (!Boolean.TRUE.equals(existing.getEditable()) || !Boolean.TRUE.equals(existing.getVisible())) {
            existing.setVisible(true);
            existing.setEditable(true);
            existing.setUpdatedAt(Instant.now());
            fieldBindingMapper.updateById(existing);
        }
    }
}
