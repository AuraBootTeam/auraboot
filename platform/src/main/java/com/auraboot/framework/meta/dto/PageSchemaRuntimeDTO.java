package com.auraboot.framework.meta.dto;

/** Immutable runtime-version identity used to partition client and edge schema caches. */
public record PageSchemaRuntimeDTO(
        String source,
        String releasePid,
        long channelVersion,
        long sourceVersion,
        String snapshotChecksum,
        String cacheKey) {
}
