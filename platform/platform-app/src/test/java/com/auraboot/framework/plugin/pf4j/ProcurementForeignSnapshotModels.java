package com.auraboot.framework.plugin.pf4j;

import java.util.List;

/** One immutable domain roster for native producers and current installed HTTP isolation evidence. */
final class ProcurementForeignSnapshotModels {
    private static final List<String> MODELS=List.of("pr_supplier_common","pr_supplier_pcba_contact","s2p_sourcing_event","pr_purchase_request","s2p_bid",
            "pr_purchase_order","pr_purchase_order_line","qo_procurement_membership_common","qo_procurement_quote_line_common","qo_procurement_history_common",
            "qo_procurement_allocation_common","qo_procurement_execution_common","qo_procurement_address_common","qo_procurement_shipment_common",
            "qo_procurement_shipment_line_common","qo_procurement_receipt_common","qo_procurement_reminder_common","qo_procurement_submission_common",
            "qo_procurement_submission_line_common","qo_procurement_category_policy_common","qo_procurement_category_supplier_common",
            "qo_procurement_inquiry_request_common","qo_procurement_deletion_scope_common","qo_procurement_deletion_notice_common",
            "qo_procurement_order_notice_common","qo_procurement_logistics_common","qo_procurement_branding_common",
            "qo_procurement_supplier_category_common","qo_procurement_supplier_categories_common","qo_procurement_material_type_common",
            "qo_procurement_receiving_address_common");
    private ProcurementForeignSnapshotModels() {}
    static List<String> all() { return MODELS; }
}
