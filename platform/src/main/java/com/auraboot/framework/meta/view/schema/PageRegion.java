package com.auraboot.framework.meta.view.schema;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type"
)
@JsonSubTypes({
    @JsonSubTypes.Type(value = FilterRegion.class, name = "filters"),
    @JsonSubTypes.Type(value = PresetRegion.class, name = "preset"),
    @JsonSubTypes.Type(value = ActionRegion.class, name = "action"),
    @JsonSubTypes.Type(value = FormRegion.class, name = "form"),
    @JsonSubTypes.Type(value = TableRegion.class, name = "table")

})
public interface PageRegion {

    String  getType();
}
