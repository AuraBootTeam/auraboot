package com.auraboot.framework.bpm.config;

import com.auraboot.smart.framework.engine.configuration.VariablePersister;
import com.auraboot.smart.framework.engine.constant.RequestMapSpecialKeyConstant;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;

/**
 * Enables SmartEngine variable persistence so that process variables
 * (applicantUserId, days, type, startUserId, etc.) survive userTask
 * wait points by being stored in se_variable_instance.
 *
 * <p>The default {@code DefaultVariablePersister} returns
 * {@code isPersisteVariableInstanceEnabled() = false}, which means all
 * process variables vanish after the first wait point.
 */
public class AuraVariablePersister implements VariablePersister {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Internal SmartEngine keys (from {@link RequestMapSpecialKeyConstant})
     * plus engine-internal keys that should NOT be persisted to the variable table.
     */
    private static final Set<String> BLOCK_LIST = new HashSet<>();

    static {
        // Collect all RequestMapSpecialKeyConstant string fields
        try {
            for (Field field : RequestMapSpecialKeyConstant.class.getDeclaredFields()) {
                if (field.getType() == String.class) {
                    String value = (String) field.get(null);
                    BLOCK_LIST.add(value);
                }
            }
        } catch (IllegalAccessException e) {
            // Should not happen — all fields are public static final
        }
        // Additional engine-internal keys
        BLOCK_LIST.add("_ruleResult");
        BLOCK_LIST.add("_chain_nodes");
    }

    @Override
    public boolean isPersisteVariableInstanceEnabled() {
        return true;
    }

    @Override
    public Set<String> getBlockList() {
        return BLOCK_LIST;
    }

    @Override
    public String serialize(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String) {
            return (String) value;
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    @Override
    public Object deserialize(String key, String type, String value) {
        if (value == null) {
            return null;
        }
        // Restore original types so Drools MVEL constraints like
        // ((Number) this["days"]).doubleValue() work across segments.
        return switch (type) {
            case "java.lang.String", "string" -> value;
            case "java.lang.Integer", "int" -> {
                try { yield Integer.parseInt(value); }
                catch (NumberFormatException e) { yield value; }
            }
            case "java.lang.Long", "long" -> {
                try { yield Long.parseLong(value); }
                catch (NumberFormatException e) { yield value; }
            }
            case "java.lang.Double", "double" -> {
                try { yield Double.parseDouble(value); }
                catch (NumberFormatException e) { yield value; }
            }
            case "java.lang.Boolean", "boolean" -> Boolean.parseBoolean(value);
            default -> {
                try { yield MAPPER.readValue(value, Object.class); }
                catch (Exception e) { yield value; }
            }
        };
    }
}
