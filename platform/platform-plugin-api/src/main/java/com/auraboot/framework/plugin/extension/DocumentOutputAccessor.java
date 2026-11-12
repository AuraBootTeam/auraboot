package com.auraboot.framework.plugin.extension;

import java.util.Map;

/** Current field view for the admitted document output; never a data read or write capability. */
public interface DocumentOutputAccessor {
    String SETTINGS_KEY = "__documentOutputAccessor";
    Map<String,Object> project(String model, Map<String,Object> source);
    boolean isUnrestricted(String model);
}
