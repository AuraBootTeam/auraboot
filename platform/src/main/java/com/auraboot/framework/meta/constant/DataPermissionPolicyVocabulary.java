package com.auraboot.framework.meta.constant;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;

import java.util.Locale;
import java.util.Set;

/** One vocabulary for policy authoring and legacy runtime consumption. */
public final class DataPermissionPolicyVocabulary {
    private DataPermissionPolicyVocabulary() { }
    private static final Set<String> TYPES=Set.of("row","column","field_write");
    private static final Set<String> SCOPES=Set.of("all","self","department","department_tree","project","custom");
    private static final Set<String> MASKS=Set.of("hide","partial","hash","custom");

    public static String token(String value) {
        return value==null?"":value.trim().toLowerCase(Locale.ROOT);
    }
    public static boolean supportedType(String value) {return TYPES.contains(token(value));}
    public static String type(String value) {return require(value,TYPES,"policy type");}
    public static String scope(String value) {return require(value,SCOPES,"row scope");}
    public static String mask(String value) {return require(value,MASKS,"field mask");}
    private static String require(String value,Set<String> vocabulary,String label) {
        String normalized=token(value);
        if(!vocabulary.contains(normalized)) throw new BusinessException(ResponseCode.BadParam,"Invalid data permission "+label);
        return normalized;
    }
}
