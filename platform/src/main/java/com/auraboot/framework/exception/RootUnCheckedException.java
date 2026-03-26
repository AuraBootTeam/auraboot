package com.auraboot.framework.exception;

import com.auraboot.framework.common.constant.ResponseCode;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RootUnCheckedException extends RuntimeException{

    private static final long serialVersionUID = -5943421879074551385L;

    private static final Logger LOG = LoggerFactory.getLogger(RootUnCheckedException.class);


    @Getter
    private ResponseCode responseCode;

    @Getter
    private Object context;

    public RootUnCheckedException(ResponseCode responseCode) {
        this.responseCode = responseCode;
    }

    public RootUnCheckedException(ResponseCode responseCode, Object context) {
        super(context.toString());

        if(context instanceof Throwable){
            Throwable e = (Throwable) context;
            LOG.error(e.getMessage(),e);
        }

        this.responseCode = responseCode;
        this.context = context;
    }


}
