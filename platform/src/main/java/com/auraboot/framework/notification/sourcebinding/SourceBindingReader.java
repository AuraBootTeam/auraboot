package com.auraboot.framework.notification.sourcebinding;

import com.auraboot.framework.plugin.extension.DataPage;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.AbstractMap;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Set;

/** Host-private finite reads. Not a PF4J or SDK authority capability. */
interface SourceBindingReader {
    /** Host-private live-read evidence, not a physical deletion marker or plugin payload claim. */
    final class LiveRecord extends AbstractMap<String, Object> {
        private final Map<String, Object> fields;

        LiveRecord(Map<String, Object> fields, boolean softDelete) {
            SourceBindingTypes.require(!softDelete || Boolean.FALSE.equals(fields.get("deleted_flag")));
            this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        }

        @Override
        public Set<Entry<String, Object>> entrySet() {
            return fields.entrySet();
        }
    }

    JsonNode metadata(long tenant, SourceBindingTypes.Approval approval);
    Map<String, Object> record(SourceBindingTypes.Binding binding, String model, String pid);
    DataPage page(SourceBindingTypes.Binding binding, String model, Map<String, Object> filters, int page, int size);
    JsonNode rule(long tenant, long id);
    JsonNode template(long tenant, String code);
    String userPid(long tenant, long id);
}
