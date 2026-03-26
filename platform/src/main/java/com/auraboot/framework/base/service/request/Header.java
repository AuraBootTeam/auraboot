package com.auraboot.framework.base.service.request;

import java.io.Serializable;

public interface Header extends Serializable {


    String getUserPid();

    void setUserPid(String userPid);

    String getAction();

    void setAction(String action);


    String getVersion();

    String getToken();
}
