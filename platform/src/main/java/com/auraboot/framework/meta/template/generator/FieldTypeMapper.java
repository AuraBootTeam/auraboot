package com.auraboot.framework.meta.template.generator;

import com.auraboot.framework.meta.entity.Field;
import org.springframework.stereotype.Component;

/**
 * Field type to component mapper
 * Maps field types to appropriate Smart Components
 * 
 * @author AuraBoot
 */
@Component
public class FieldTypeMapper {
    
    /**
     * Get form component for field type
     * 
     * @param field Field entity
     * @return Component name
     */
    public String getFormComponent(Field field) {
        if (field == null || field.getDataType() == null) {
            return "SmartInput";
        }
        
        String dataType = field.getDataType().toUpperCase();
        
        return switch (dataType) {
            case "string" -> "SmartInput";
            case "text" -> "SmartTextarea";
            case "integer", "decimal", "number" -> "SmartInput";
            case "money" -> "SmartMoneyInput";
            case "boolean" -> "SmartSwitch";
            case "date" -> "SmartDatePicker";
            case "datetime", "timestamp" -> "SmartDateTimePicker";
            case "enum", "reference" -> "SmartSelect";
            default -> "SmartInput";
        };
    }
    
    /**
     * Get column value type for list display
     * 
     * @param field Field entity
     * @return Value type
     */
    public String getColumnValueType(Field field) {
        if (field == null || field.getDataType() == null) {
            return "text";
        }
        
        String dataType = field.getDataType().toUpperCase();
        
        return switch (dataType) {
            case "boolean" -> "tag";
            case "date" -> "date";
            case "datetime", "timestamp" -> "datetime";
            case "enum" -> "tag";
            case "money" -> "currency";
            default -> "text";
        };
    }
    
    /**
     * Check if field needs number input type
     * 
     * @param field Field entity
     * @return true if number type
     */
    public boolean isNumberField(Field field) {
        if (field == null || field.getDataType() == null) {
            return false;
        }
        
        String dataType = field.getDataType().toUpperCase();
        return "integer".equals(dataType) ||
               "decimal".equals(dataType) ||
               "number".equals(dataType) ||
               "money".equals(dataType);
    }
}
