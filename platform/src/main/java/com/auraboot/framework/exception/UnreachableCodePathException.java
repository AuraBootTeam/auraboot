package com.auraboot.framework.exception;


import com.auraboot.framework.common.constant.ResponseCode;

public class UnreachableCodePathException extends RootUnCheckedException {


    private static final long serialVersionUID = -4628485572389136720L;


    public UnreachableCodePathException(ResponseCode responseCode) {
        super(responseCode);
    }

    public UnreachableCodePathException(ResponseCode responseCode, Object context) {
      super(responseCode,context);

    }


}
