package com.auraboot.framework.base.service.request;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Data;

@Data
public class CompositeRequest implements BaseRequest {

    @JsonDeserialize(as = DefaultHeader.class)
    private Header header;

    @JsonDeserialize(as = CompositePayLoad.class)
    private Payload payload;

    @JsonDeserialize(as = DefaultExtension.class)
    private Extension extension;

}
