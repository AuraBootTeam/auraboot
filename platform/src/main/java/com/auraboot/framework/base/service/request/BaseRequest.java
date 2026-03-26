package com.auraboot.framework.base.service.request;

public interface BaseRequest {

    public Header getHeader();

    public void setHeader(Header header);

    public Payload getPayload();

    public void setPayload(Payload payload);

    public Extension getExtension();

    public void setExtension(Extension extension);


}
