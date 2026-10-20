package com.auraboot.framework.plugin.pf4j;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;

/** Exact opt-in P14/foreign prerequisites, never a production writer identity. */
final class ProcurementLinkedForeignInitialization {
    static final Map<String, List<String>> FIELDS = fields();
    private static Map<String, List<String>> fields() {
        var fields = new LinkedHashMap<>(ProcurementSupplierUiInitialization.FIELDS);
        fields.remove("pr_purchase_order");
        fields.remove("pr_purchase_order_line");
        fields.remove("pr_purchase_request");
        fields.put("pr_supplier_pcba_contact", List.of("pr_spc_supplier_id", "pr_spc_contact_name",
                "qo_pr_contact_state", "qo_pr_contact_normalized_phone"));
        fields.put("customer_pcba_int_external_ref", List.of("customer_pcba_ref_external_system",
                "customer_pcba_ref_instance_key", "customer_pcba_ref_account_id", "customer_pcba_ref_external_form_id",
                "customer_pcba_ref_external_id", "customer_pcba_ref_local_model_code", "customer_pcba_ref_local_pid"));
        return Map.copyOf(fields);
    }
    static String command(String model) {
        if (!FIELDS.containsKey(model)) throw new IllegalArgumentException("Unknown linked fixture model");
        return model + ":init_linked_fixture";
    }
    static void validatePayload(String model, Map<String, Object> payload) {
        if (!FIELDS.containsKey(model) || !Set.copyOf(FIELDS.get(model)).equals(payload.keySet()))
            throw new IllegalArgumentException("Exact linked fixture facts required");
    }
    static Map<String, Object> prepare(List<Path> sources, Path destination, ObjectMapper json) throws Exception {
        return ProcurementSupplierUiInitialization.prepareOwned(sources, destination, json, FIELDS,
                ProcurementLinkedForeignInitialization::command);
    }
}
