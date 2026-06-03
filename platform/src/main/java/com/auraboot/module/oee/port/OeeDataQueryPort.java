package com.auraboot.module.oee.port;

import com.auraboot.module.oee.dto.OeeInputs;
import com.auraboot.module.oee.dto.OeeRequest;

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
}
