package com.auraboot.framework.exception;


import com.auraboot.framework.common.constant.ResponseCode;

public class UnSupportedException extends RootUnCheckedException {


    private static final long serialVersionUID = -4628485572389136720L;


    public UnSupportedException(ResponseCode responseCode) {
        super(responseCode);
    }

    public UnSupportedException(ResponseCode responseCode, Object context) {
      super(responseCode,context);

    }


}
