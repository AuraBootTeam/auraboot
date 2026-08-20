package com.auraboot.plugins.crm.handler;

import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.extension.DataAccessor;
import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts a qualified lead into the generic CRM relationship graph.
 *
 * <p>The handler intentionally stops at the generic Customer Request boundary.
 * Industry packages can route that request into RFQ/BOM/DFM without polluting
 * the base Lead/Account model with industry-specific fields.
 */
@Extension
public class ConvertLeadHandler implements CommandHandlerExtension {

    private static final Logger log = LoggerFactory.getLogger(ConvertLeadHandler.class);

    public static final String COMMAND_TYPE = "crm:convert_lead";

    @Override
    public String getCommandType() {
        return COMMAND_TYPE;
    }

    @Override
    public Set<String> getSupportedCommandTypes() {
        return Set.of(COMMAND_TYPE);
    }

    @Override
    public boolean supports(String commandType) {
        return COMMAND_TYPE.equals(commandType);
    }

    @Override
    public Object execute(CommandContext context) throws Exception {
        DataAccessor db = dataAccessor(context);
        if (db == null) {
            throw new IllegalStateException("DataAccessor unavailable; cannot convert lead");
        }
        String leadId = context.recordId();
        if (isBlank(leadId)) {
            throw new IllegalStateException("No lead record id on convert context");
        }

        Map<String, Object> lead = db.getById("crm_lead_common", leadId);
        if (lead == null) {
            throw new IllegalStateException("Lead not found for conversion: " + leadId);
        }
        String status = str(lead.get("crm_lead_status"));
        if (!isBlank(lead.get("crm_lead_converted_opportunity_id")) || "converted".equals(status)) {
            throw new IllegalArgumentException("Lead already converted: " + leadId);
        }
        if (!"qualified".equals(status)) {
            throw new IllegalArgumentException("Only qualified leads can be converted: " + leadId);
        }

        String leadPid = nonBlank(str(lead.get("pid")), leadId);
        String leadCode = nonBlank(str(lead.get("crm_lead_code")), leadPid);
        String company = required(lead.get("crm_lead_company"), "crm_lead_company is required before conversion");
        String owner = nonBlank(str(lead.get("crm_lead_assigned_to")), context.currentUserPid());
        if (isBlank(owner)) {
            throw new IllegalStateException("Lead conversion requires an assigned owner or authenticated actor");
        }

        Map<String, Object> account = findOrCreateAccount(db, lead, leadCode, company, owner);
        String accountId = resolveId(account);
        if (isBlank(accountId)) {
            throw new IllegalStateException("Converted account id is empty for lead " + leadId);
        }

        Map<String, Object> contact = findOrCreateContact(db, lead, accountId);
        String contactId = resolveId(contact);

        Map<String, Object> opportunity = createOpportunity(db, lead, leadPid, leadCode, company, accountId, owner);
        String opportunityId = resolveId(opportunity);

        Map<String, Object> request = createCustomerRequest(
                db, lead, leadPid, leadCode, company, accountId, contactId, opportunityId, owner);
        String requestId = resolveId(request);

        Map<String, Object> update = new HashMap<>();
        update.put("crm_lead_status", "converted");
        update.put("crm_lead_converted_account_id", accountId);
        if (!isBlank(contactId)) {
            update.put("crm_lead_converted_contact_id", contactId);
        }
        update.put("crm_lead_converted_opportunity_id", opportunityId);
        update.put("crm_lead_converted_request_id", requestId);
        update.put("crm_lead_converted_at", Instant.now().toString());
        db.update("crm_lead_common", leadPid, update);

        log.info("Converted lead {} into account={}, contact={}, opportunity={}, request={}",
                leadPid, accountId, contactId, opportunityId, requestId);

        return Map.of(
                "success", true,
                "leadId", leadPid,
                "accountId", accountId,
                "contactId", contactId == null ? "" : contactId,
                "opportunityId", opportunityId,
                "customerRequestId", requestId);
    }

    private static Map<String, Object> findOrCreateAccount(
            DataAccessor db, Map<String, Object> lead, String leadCode, String company, String owner) {
        List<Map<String, Object>> existing = db.query("crm_account_common", Map.of("crm_acc_name", company));
        if (existing != null && !existing.isEmpty()) {
            return existing.getFirst();
        }

        Map<String, Object> data = new HashMap<>();
        data.put("crm_acc_code", code("ACC", leadCode));
        data.put("crm_acc_name", company);
        data.put("crm_acc_industry", lead.get("crm_lead_industry"));
        data.put("crm_acc_phone", lead.get("crm_lead_contact_phone"));
        data.put("crm_acc_owner", owner);
        data.put("crm_acc_status", "active");
        data.put("crm_acc_pool_state", "owned");
        data.put("crm_acc_remark", "Created by crm:convert_lead from " + leadCode);
        return db.create("crm_account_common", data);
    }

    private static Map<String, Object> findOrCreateContact(DataAccessor db, Map<String, Object> lead, String accountId) {
        String name = str(lead.get("crm_lead_contact_name"));
        if (isBlank(name)) {
            return Map.of();
        }
        String email = str(lead.get("crm_lead_contact_email"));
        if (!isBlank(email)) {
            List<Map<String, Object>> existing = db.query("crm_contact_common", Map.of(
                    "crm_ct_account_id", accountId,
                    "crm_ct_email", email));
            if (existing != null && !existing.isEmpty()) {
                return existing.getFirst();
            }
        }

        Map<String, Object> data = new HashMap<>();
        data.put("crm_ct_account_id", accountId);
        data.put("crm_ct_name", name);
        data.put("crm_ct_email", email);
        data.put("crm_ct_phone", lead.get("crm_lead_contact_phone"));
        data.put("crm_ct_mobile", lead.get("crm_lead_contact_phone"));
        data.put("crm_ct_is_primary", true);
        data.put("crm_ct_status", "active");
        return db.create("crm_contact_common", data);
    }

    private static Map<String, Object> createOpportunity(
            DataAccessor db,
            Map<String, Object> lead,
            String leadPid,
            String leadCode,
            String company,
            String accountId,
            String owner) {
        Map<String, Object> data = new HashMap<>();
        data.put("crm_opp_code", code("OPP", leadCode));
        data.put("crm_opp_name", company);
        data.put("crm_opp_account_id", accountId);
        data.put("crm_opp_lead_id", leadPid);
        data.put("crm_opp_stage", "qualification");
        data.put("crm_opp_probability", 25);
        data.put("crm_opp_owner", owner);
        data.put("crm_opp_forecast_category", "pipeline");
        data.put("crm_opp_notes", nonBlank(str(lead.get("crm_lead_requirement")), "Created by crm:convert_lead"));
        return db.create("crm_opportunity_common", data);
    }

    private static Map<String, Object> createCustomerRequest(
            DataAccessor db,
            Map<String, Object> lead,
            String leadPid,
            String leadCode,
            String company,
            String accountId,
            String contactId,
            String opportunityId,
            String owner) {
        String requirement = str(lead.get("crm_lead_requirement"));
        Map<String, Object> data = new HashMap<>();
        data.put("crm_cr_code", code("CR", leadCode));
        data.put("crm_cr_title", nonBlank(firstLine(requirement), company + " initial request"));
        data.put("crm_cr_account_id", accountId);
        if (!isBlank(contactId)) {
            data.put("crm_cr_contact_id", contactId);
        }
        data.put("crm_cr_lead_id", leadPid);
        data.put("crm_cr_opportunity_id", opportunityId);
        data.put("crm_cr_type", "inquiry");
        data.put("crm_cr_priority", "medium");
        data.put("crm_cr_status", "submitted");
        data.put("crm_cr_owner", owner);
        data.put("crm_cr_source_channel", "lead");
        data.put("crm_cr_route_status", "unrouted");
        data.put("crm_cr_summary", requirement);
        return db.create("crm_customer_request_common", data);
    }

    private static DataAccessor dataAccessor(CommandContext context) {
        DataAccessor db = context.dataAccessor();
        if (db != null) {
            return db;
        }
        Object fromSettings = context.settings() == null ? null : context.settings().get("__dataAccessor");
        return fromSettings instanceof DataAccessor dataAccessor ? dataAccessor : null;
    }

    private static String resolveId(Map<String, Object> record) {
        if (record == null || record.isEmpty()) {
            return null;
        }
        Object pid = record.get("pid");
        if (pid != null && !pid.toString().isBlank()) {
            return pid.toString();
        }
        Object id = record.get("id");
        return id == null ? null : id.toString();
    }

    private static String code(String prefix, String source) {
        String normalized = source == null ? "UNKNOWN" : source.replaceAll("[^A-Za-z0-9-]", "-");
        if (normalized.length() > 34) {
            normalized = normalized.substring(0, 34);
        }
        return prefix + "-" + normalized;
    }

    private static String firstLine(String value) {
        if (value == null) {
            return null;
        }
        String s = value.strip();
        if (s.isEmpty()) {
            return null;
        }
        int newline = s.indexOf('\n');
        String first = newline >= 0 ? s.substring(0, newline).strip() : s;
        return first.length() > 180 ? first.substring(0, 180) : first;
    }

    private static String required(Object value, String message) {
        String s = str(value);
        if (isBlank(s)) {
            throw new IllegalArgumentException(message);
        }
        return s;
    }

    private static boolean isBlank(Object value) {
        return value == null || value.toString().isBlank();
    }

    private static String nonBlank(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }
}
