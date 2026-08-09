package com.auraboot.framework.authoring.policy;

import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.CapabilityManifest;

import java.util.Collection;
import java.util.Optional;

/** Server-owned capability source. Unknown block types fail closed. */
public interface AuthoringCapabilityRegistry {

    Optional<CapabilityManifest> find(String blockType);

    Collection<CapabilityManifest> all();

    /** Checksum over the complete server-owned registry snapshot. */
    String checksum();
}
