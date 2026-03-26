package com.auraboot.framework.base.service.request;

import java.io.Serializable;

public interface Payload<T> extends Serializable {

    T getData();

    void setData(T data);

}
