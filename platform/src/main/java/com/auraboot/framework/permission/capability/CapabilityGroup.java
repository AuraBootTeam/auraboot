package com.auraboot.framework.permission.capability;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** A folded group of capabilities in the permission v2 UI (e.g. "客户管理"). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CapabilityGroup {
    private String group;
    private List<Capability> capabilities;
}
