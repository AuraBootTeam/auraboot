package com.auraboot.plugins.crm.handler;

import com.auraboot.framework.plugin.extension.CommandHandlerExtension.CommandContext;
import com.auraboot.framework.plugin.extension.DataAccessor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConvertLeadHandlerTest {

    private final ConvertLeadHandler handler = new ConvertLeadHandler();

    @Test
    void convertsQualifiedLeadIntoGenericCrmGraph() throws Exception {
        FakeDataAccessor db = new FakeDataAccessor();
        db.seed("crm_lead_common", row(
                "pid", "lead-1",
                "crm_lead_code", "LEAD-001",
                "crm_lead_company", "Acme PCBA",
                "crm_lead_contact_name", "Ada Chen",
                "crm_lead_contact_email", "ada@example.com",
                "crm_lead_contact_phone", "13800000000",
                "crm_lead_source", "web",
                "crm_lead_industry", "electronics",
                "crm_lead_assigned_to", "sales-a",
                "crm_lead_requirement", "Need a fast quote for EV controller board\nAnnual volume 20k",
                "crm_lead_status", "qualified"
        ));
        db.seed("crm_activity_common", row(
                "pid", "activity-direct",
                "crm_act_type", "visit",
                "crm_act_subject", "Lead site visit",
                "crm_act_related_model", "crm_lead_common",
                "crm_act_related_id", "lead-1"
        ));
        db.seed("crm_activity_common", row(
                "pid", "activity-related",
                "crm_act_type", "task",
                "crm_act_subject", "Lead follow-up plan"
        ));
        db.seed("crm_activity_relation_common", row(
                "pid", "relation-lead",
                "crm_ar_activity_id", "activity-related",
                "crm_ar_object_type", "lead",
                "crm_ar_object_id", "lead-1",
                "crm_ar_role", "primary"
        ));

        Object result = handler.execute(context("lead-1", db));

        assertTrue(result instanceof Map<?, ?>);
        Map<?, ?> conversion = (Map<?, ?>) result;
        assertEquals(2, conversion.get("carriedActivityCount"));
        assertEquals(7, conversion.get("createdActivityRelationCount"));
        assertEquals(1, db.store.get("crm_account_common").size());
        assertEquals(1, db.store.get("crm_contact_common").size());
        assertEquals(1, db.store.get("crm_opportunity_common").size());
        assertEquals(1, db.store.get("crm_customer_request_common").size());

        Map<String, Object> account = db.store.get("crm_account_common").getFirst();
        assertEquals("Acme PCBA", account.get("crm_acc_name"));
        assertEquals("electronics", account.get("crm_acc_industry"));
        assertEquals("sales-a", account.get("crm_acc_owner"));
        assertEquals("owned", account.get("crm_acc_pool_state"));

        Map<String, Object> contact = db.store.get("crm_contact_common").getFirst();
        assertEquals("PID1", contact.get("crm_ct_account_id"));
        assertEquals("Ada Chen", contact.get("crm_ct_name"));
        assertEquals("ada@example.com", contact.get("crm_ct_email"));
        assertEquals(true, contact.get("crm_ct_is_primary"));
        assertEquals("PID1", contact.get("crm_ct_primary_account_key"));

        Map<String, Object> opportunity = db.store.get("crm_opportunity_common").getFirst();
        assertEquals("PID1", opportunity.get("crm_opp_account_id"));
        assertEquals("lead-1", opportunity.get("crm_opp_lead_id"));
        assertEquals("qualification", opportunity.get("crm_opp_stage"));
        assertEquals("pipeline", opportunity.get("crm_opp_forecast_category"));

        Map<String, Object> request = db.store.get("crm_customer_request_common").getFirst();
        assertEquals("PID1", request.get("crm_cr_account_id"));
        assertEquals("PID2", request.get("crm_cr_contact_id"));
        assertEquals("PID3", request.get("crm_cr_opportunity_id"));
        assertEquals("lead-1", request.get("crm_cr_lead_id"));
        assertEquals("submitted", request.get("crm_cr_status"));
        assertEquals("unrouted", request.get("crm_cr_route_status"));
        assertEquals("lead", request.get("crm_cr_source_channel"));
        assertEquals("Need a fast quote for EV controller board", request.get("crm_cr_title"));

        Map<String, Object> lead = db.getById("crm_lead_common", "lead-1");
        assertEquals("converted", lead.get("crm_lead_status"));
        assertEquals("PID1", lead.get("crm_lead_converted_account_id"));
        assertEquals("PID2", lead.get("crm_lead_converted_contact_id"));
        assertEquals("PID3", lead.get("crm_lead_converted_opportunity_id"));
        assertEquals("PID4", lead.get("crm_lead_converted_request_id"));
        assertFalse(String.valueOf(lead.get("crm_lead_converted_at")).isBlank());

        List<Map<String, Object>> relations = db.store.get("crm_activity_relation_common");
        assertEquals(8, relations.size());
        for (String activityId : List.of("activity-direct", "activity-related")) {
            assertTrue(hasEdge(relations, activityId, "lead", "lead-1"));
            assertTrue(hasEdge(relations, activityId, "account", "PID1"));
            assertTrue(hasEdge(relations, activityId, "contact", "PID2"));
            assertTrue(hasEdge(relations, activityId, "opportunity", "PID3"));
        }
        Map<String, Object> directActivity = db.getById("crm_activity_common", "activity-direct");
        assertEquals("crm_lead_common", directActivity.get("crm_act_related_model"));
        assertEquals("lead-1", directActivity.get("crm_act_related_id"));
    }

    @Test
    void reusesExistingAccountAndSkipsContactWhenNoContactName() throws Exception {
        FakeDataAccessor db = new FakeDataAccessor();
        db.seed("crm_account_common", Map.of(
                "pid", "acc-1",
                "crm_acc_name", "Existing Account",
                "crm_acc_status", "active"
        ));
        db.seed("crm_lead_common", Map.of(
                "pid", "lead-2",
                "crm_lead_code", "LEAD-002",
                "crm_lead_company", "Existing Account",
                "crm_lead_requirement", "Expansion request",
                "crm_lead_status", "qualified"
        ));

        handler.execute(context("lead-2", db));

        assertEquals(1, db.store.get("crm_account_common").size());
        assertEquals(null, db.store.get("crm_contact_common"));
        Map<String, Object> lead = db.getById("crm_lead_common", "lead-2");
        assertEquals("acc-1", lead.get("crm_lead_converted_account_id"));
        assertEquals(null, lead.get("crm_lead_converted_contact_id"));
    }

    @Test
    void usesAuthenticatedActorAsOwnerWhenLeadIsUnassigned() throws Exception {
        FakeDataAccessor db = new FakeDataAccessor();
        db.seed("crm_lead_common", Map.of(
                "pid", "lead-unassigned",
                "crm_lead_code", "LEAD-UNASSIGNED",
                "crm_lead_company", "First-use Customer",
                "crm_lead_status", "qualified"
        ));

        handler.execute(context("lead-unassigned", db));

        assertEquals("actor-1", db.store.get("crm_account_common").getFirst().get("crm_acc_owner"));
        assertEquals("actor-1", db.store.get("crm_opportunity_common").getFirst().get("crm_opp_owner"));
        assertEquals("actor-1", db.store.get("crm_customer_request_common").getFirst().get("crm_cr_owner"));
    }

    @Test
    void rejectsNonQualifiedLead() {
        FakeDataAccessor db = new FakeDataAccessor();
        db.seed("crm_lead_common", Map.of(
                "pid", "lead-3",
                "crm_lead_company", "Not Ready",
                "crm_lead_status", "new"
        ));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> handler.execute(context("lead-3", db))
        );

        assertTrue(error.getMessage().contains("Only qualified leads"));
    }

    @Test
    void rejectsAlreadyConvertedLead() {
        FakeDataAccessor db = new FakeDataAccessor();
        db.seed("crm_lead_common", Map.of(
                "pid", "lead-4",
                "crm_lead_company", "Converted",
                "crm_lead_status", "converted",
                "crm_lead_converted_opportunity_id", "opp-1"
        ));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> handler.execute(context("lead-4", db))
        );

        assertTrue(error.getMessage().contains("already converted"));
    }

    @Test
    void convertsQualifiedLeadToCustomerWithoutOpportunityOrRequest() throws Exception {
        FakeDataAccessor db = new FakeDataAccessor();
        db.seed("crm_lead_common", row(
                "pid", "lead-customer-only",
                "crm_lead_code", "LEAD-CUSTOMER-ONLY",
                "crm_lead_company", "Customer Only Ltd",
                "crm_lead_contact_name", "Casey Lin",
                "crm_lead_contact_email", "casey@example.com",
                "crm_lead_assigned_to", "sales-a",
                "crm_lead_status", "qualified"
        ));
        db.seed("crm_activity_common", row(
                "pid", "activity-customer-only",
                "crm_act_related_model", "crm_lead_common",
                "crm_act_related_id", "lead-customer-only"
        ));

        Map<?, ?> result = (Map<?, ?>) handler.execute(context(
                "lead-customer-only", db, ConvertLeadHandler.CUSTOMER_ONLY_COMMAND_TYPE));

        assertEquals("", result.get("opportunityId"));
        assertEquals("", result.get("customerRequestId"));
        assertEquals(1, db.store.get("crm_account_common").size());
        assertEquals(1, db.store.get("crm_contact_common").size());
        assertFalse(db.store.containsKey("crm_opportunity_common"));
        assertFalse(db.store.containsKey("crm_customer_request_common"));
        assertEquals(3, result.get("createdActivityRelationCount"));

        Map<String, Object> lead = db.getById("crm_lead_common", "lead-customer-only");
        assertEquals("converted", lead.get("crm_lead_status"));
        assertEquals("PID1", lead.get("crm_lead_converted_account_id"));
        assertEquals("PID2", lead.get("crm_lead_converted_contact_id"));
        assertFalse(lead.containsKey("crm_lead_converted_opportunity_id"));
        assertFalse(lead.containsKey("crm_lead_converted_request_id"));
    }

    private static CommandContext context(String recordId, DataAccessor db) {
        return context(recordId, db, ConvertLeadHandler.COMMAND_TYPE);
    }

    private static CommandContext context(String recordId, DataAccessor db, String commandType) {
        return CommandContext.builder()
                .tenantId(1L)
                .pluginId("com.auraboot.crm")
                .namespace("crm")
                .commandType(commandType)
                .modelCode("crm_lead_common")
                .recordId(recordId)
                .settings(Map.of("__dataAccessor", db, "__currentUserPid", "actor-1"))
                .dryRun(false)
                .build();
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> data = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            data.put((String) kv[i], kv[i + 1]);
        }
        return data;
    }

    private static boolean hasEdge(
            List<Map<String, Object>> relations,
            String activityId,
            String objectType,
            String objectId) {
        return relations.stream().anyMatch(relation ->
                activityId.equals(relation.get("crm_ar_activity_id"))
                        && objectType.equals(relation.get("crm_ar_object_type"))
                        && objectId.equals(relation.get("crm_ar_object_id")));
    }

    private static final class FakeDataAccessor implements DataAccessor {
        private final Map<String, List<Map<String, Object>>> store = new HashMap<>();
        private int seq = 0;

        void seed(String model, Map<String, Object> row) {
            store.computeIfAbsent(model, ignored -> new ArrayList<>()).add(new HashMap<>(row));
        }

        @Override
        public Map<String, Object> getById(String modelCode, String recordId) {
            return store.getOrDefault(modelCode, List.of()).stream()
                    .filter(row -> recordId.equals(row.get("pid")) || recordId.equals(String.valueOf(row.get("id"))))
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public List<Map<String, Object>> query(String modelCode, Map<String, Object> filters) {
            return store.getOrDefault(modelCode, List.of()).stream()
                    .filter(row -> filters.entrySet().stream()
                            .allMatch(entry -> String.valueOf(entry.getValue()).equals(String.valueOf(row.get(entry.getKey())))))
                    .toList();
        }

        @Override
        public Map<String, Object> create(String modelCode, Map<String, Object> data) {
            Map<String, Object> record = new HashMap<>(data);
            record.put("pid", "PID" + (++seq));
            record.put("id", seq);
            store.computeIfAbsent(modelCode, ignored -> new ArrayList<>()).add(record);
            return record;
        }

        @Override
        public Map<String, Object> update(String modelCode, String recordId, Map<String, Object> data) {
            Map<String, Object> record = getById(modelCode, recordId);
            if (record != null) {
                record.putAll(data);
            }
            return record;
        }

        @Override
        public List<Map<String, Object>> batchCreate(String modelCode, List<Map<String, Object>> dataList) {
            return dataList.stream().map(data -> create(modelCode, data)).toList();
        }

        @Override
        public void delete(String modelCode, String recordId) {
            store.getOrDefault(modelCode, new ArrayList<>()).removeIf(row ->
                    recordId.equals(row.get("pid")) || recordId.equals(String.valueOf(row.get("id"))));
        }
    }
}
