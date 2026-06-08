package com.auraboot.framework.decision;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.eventpolicy.model.ConflictStrategy;
import com.auraboot.framework.eventpolicy.model.DedupStrategy;
import com.auraboot.framework.eventpolicy.model.EventPolicyExecutionResult;
import com.auraboot.framework.eventpolicy.model.ExecutionMode;
import com.auraboot.framework.eventpolicy.model.FailureStrategy;
import com.auraboot.framework.eventpolicy.model.MatchMode;
import com.auraboot.framework.eventpolicy.model.PolicyPhase;
import com.auraboot.framework.eventpolicy.service.EventPolicyDefinitionService;
import com.auraboot.framework.eventpolicy.service.EventPolicyRuntimeService;
import com.auraboot.framework.eventpolicy.service.EventPolicyVersionService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.constant.Status;
import com.auraboot.framework.meta.dto.AddFieldRequest;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.entity.payload.FieldFeatureBean;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaFieldService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UPDATE_RECORD action end-to-end over the real stack: a published EventPolicy with an UPDATE_RECORD
 * action runs through the executor + production {@code UpdateRecordActionHandler} and mutates a real
 * dynamic record via {@code DynamicDataService} (docs/2.md §7). Builds a transient meta-model (seed
 * field + bound + published so the table exists, then addToModel a 'status' column), creates a record,
 * fires the policy, and asserts the record's field changed. Promotes the handler's unit test to a
 * real-record-mutation IT.
 */
// NOT_SUPPORTED (no test tx) so the seed model/field/binding COMMIT and publish's DDL connection
// sees them (mirrors MetaFieldServiceAddToModelIntegrationTest); @AfterEach cleans up.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UpdateRecordE2EIntegrationTest extends BaseIntegrationTest {

    @Autowired private MetaModelService metaModelService;
    @Autowired private MetaFieldService metaFieldService;
    @Autowired private DynamicDataService dynamicDataService;
    @Autowired private MetaModelMapper metaModelMapper;
    @Autowired private MetaFieldMapper metaFieldMapper;
    @Autowired private MetaModelFieldBindingMapper bindingMapper;
    @Autowired private DynamicDataMapper dynamicDataMapper;
    @Autowired private EventPolicyDefinitionService definitionService;
    @Autowired private EventPolicyVersionService versionService;
    @Autowired private EventPolicyRuntimeService runtimeService;

    private final ObjectMapper mapper = new ObjectMapper();
    private String modelCode;
    private String tableName;
    private String statusField;
    private Model model;
    private Field seedField;

    @BeforeEach
    public void setupModel() {
        super.setupTenantContext();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase();
        modelCode = "it_drt_ur_" + suffix;
        tableName = "mt_" + modelCode;
        statusField = "st_" + suffix; // unique — field codes are tenant-global

        model = buildModel(modelCode);
        metaModelMapper.insert(model);
        seedField = buildField("seed_" + suffix);
        metaFieldMapper.insert(seedField);
        ModelFieldBinding b = new ModelFieldBinding();
        b.setTenantId(getTestTenant().getId());
        b.setModelId(model.getId());
        b.setFieldId(seedField.getId());
        b.setFieldOrder(1);
        bindingMapper.insert(b);

        metaModelService.publish(model.getPid(), "drt ur IT seed publish");
        // add the mutable 'status' column on the published table
        metaFieldService.addToModel(AddFieldRequest.builder()
                .modelCode(modelCode).code(statusField).dataType("string")
                .displayName("Status").tenantId(getTestTenant().getId()).build());
    }

    @AfterEach
    public void cleanup() {
        try { dynamicDataMapper.alterTable("DROP TABLE IF EXISTS " + tableName); } catch (Exception ignore) { }
        try { if (model != null) bindingMapper.deleteByModelId(model.getId()); } catch (Exception ignore) { }
        try { if (seedField != null) metaFieldMapper.deleteById(seedField.getId()); } catch (Exception ignore) { }
        try { if (model != null) metaModelMapper.deleteById(model.getId()); } catch (Exception ignore) { }
    }

    @Test
    void updateRecordAction_mutatesRealRecord_viaRealHandler() throws Exception {
        // a record with status=OPEN
        Map<String, Object> rec = new HashMap<>();
        rec.put(seedField.getCode(), "v1");
        rec.put(statusField, "OPEN");
        Map<String, Object> created = dynamicDataService.create(modelCode, rec);
        String recordPid = String.valueOf(created.get("pid"));
        assertThat(recordPid).isNotBlank();

        // publish a policy whose UPDATE_RECORD action sets status=ESCALATED when priority=HIGH
        String code = "it_ur_pol_" + System.nanoTime();
        definitionService.create(code, "UR E2E", "FORM_SUBMITTED", "FORM", modelCode);
        JsonNode rules = mapper.readTree(("""
            [{"ruleCode":"R-UR","ruleName":"escalate","priority":100,"enabled":true,
              "condition":{"type":"compare",
                 "left":{"type":"path","scope":"record","path":"data.priority","dataType":"enum"},
                 "operator":"EQ","right":{"type":"literal","value":"HIGH","dataType":"enum"}},
              "actions":[{"type":"UPDATE_RECORD","target":"RECORD","order":10,
                 "payload":{"fields":{"%s":"ESCALATED"}},
                 "idempotencyKeyTemplate":"${record.entityCode}:${record.recordId}:${rule.ruleCode}:UR"}]}]
            """).formatted(statusField));
        var draft = versionService.createDraft(code, PolicyPhase.AFTER_COMMIT, MatchMode.COLLECT_ALL,
                ExecutionMode.ORDERED, FailureStrategy.CONTINUE_ON_ERROR, ConflictStrategy.REJECT_ON_CONFLICT,
                DedupStrategy.BY_IDEMPOTENCY_KEY, rules);
        versionService.validate(draft.getPid());
        versionService.publish(draft.getPid());

        EventPolicyExecutionResult result = runtimeService.runAndExecute("FORM_SUBMITTED", "FORM", modelCode,
                Map.of("record", Map.of("entityCode", modelCode, "recordId", recordPid,
                        "data", Map.of("priority", "HIGH"))));

        assertThat(result.policy().status().name()).isEqualTo("MATCHED");
        assertThat(result.execution().actions().get(0).status().name()).isEqualTo("SUCCESS");

        // the real record was mutated by the production UpdateRecordActionHandler -> DynamicDataService
        Map<String, Object> after = dynamicDataService.getById(modelCode, recordPid);
        assertThat(after.get(statusField)).isEqualTo("ESCALATED");
    }

    private Model buildModel(String code) {
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
        Map<String, Object> e = new HashMap<>();
        e.put("displayName", "DRT UR IT model");
        e.put("modelType", "entity");
        ext.setExtension(e);
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
        FieldFeatureBean feat = new FieldFeatureBean();
        feat.setRequired(false);
        feat.setUnique(false);
        f.setFeature(feat);
        ExtensionBean ext = new ExtensionBean();
        Map<String, Object> e = new HashMap<>();
        e.put("displayName", code);
        ext.setExtension(e);
        f.setExtension(ext);
        return f;
    }
}
