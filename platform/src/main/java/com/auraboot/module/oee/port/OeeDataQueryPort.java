package com.auraboot.module.oee.port;

import com.auraboot.module.oee.dto.OeeEquipmentRef;
import com.auraboot.module.oee.dto.OeeInputs;
import com.auraboot.module.oee.dto.OeeRequest;

import java.util.List;

/**
 * Port interface for fetching OEE raw inputs. The engine depends only on this; the real DB
 * access is isolated behind an adapter. Returns zero-valued inputs when the source plugin is
 * not imported.
 */
public interface OeeDataQueryPort {
    /**
     * Fetch all raw OEE inputs (calendar / downtime / output / capacity) for an equipment in a
     * time window. Returns zero-valued inputs when the plugin tables are absent.
     */
    OeeInputs fetch(OeeRequest request);

    /**
     * List every equipment of the tenant (id / code / name). Returns an empty list when the plugin
     * tables are absent. Used by the fleet OEE roll-up to iterate all equipment.
     */
    List<OeeEquipmentRef> listEquipment(Long tenantId);
}
