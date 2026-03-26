package com.auraboot.framework.user.exception;


import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.RootUnCheckedException;

public class UserException extends RootUnCheckedException {


    private static final long serialVersionUID = -4628485572389136720L;

//    public UserException(int code, String message) {
//        super(code, message);
//    }

    public UserException(ResponseCode code) {
        super(code);
    }



}
