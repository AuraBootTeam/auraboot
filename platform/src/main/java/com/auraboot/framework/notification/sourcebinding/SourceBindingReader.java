package com.auraboot.framework.notification.sourcebinding;

import com.auraboot.framework.plugin.extension.DataPage;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/** Host-private finite reads. Not a PF4J or SDK authority capability. */
interface SourceBindingReader {
    JsonNode metadata(long tenant, SourceBindingTypes.Approval approval);
    Map<String, Object> record(SourceBindingTypes.Binding binding, String model, String pid);
    DataPage page(SourceBindingTypes.Binding binding, String model, Map<String, Object> filters, int page, int size);
    JsonNode rule(long tenant, long id);
    JsonNode template(long tenant, String code);
    String userPid(long tenant, long id);
}
