package com.auraboot.framework.plugin.extension;

import java.util.Map;

/** Host-only, callback-lifetime source facts protected by locks in the original transaction. */
public interface BusinessResourceSourceReader extends RecordReadAccessor {
    Map<String,Object> getLockedById(String modelCode, String recordId);
    DataPage queryLocked(String modelCode, DataPageQuery query);
}
