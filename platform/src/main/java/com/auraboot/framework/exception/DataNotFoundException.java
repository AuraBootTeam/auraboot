package com.auraboot.framework.exception;


import com.auraboot.framework.common.constant.ResponseCode;

public class DataNotFoundException extends RootUnCheckedException {


    private static final long serialVersionUID = -4628485572389136720L;


    public DataNotFoundException(ResponseCode responseCode) {
        super(responseCode);
    }

    public DataNotFoundException(ResponseCode responseCode, Object context) {
      super(responseCode,context);

    }


}
