package com.auraboot.framework.common.util;

import de.huxhorn.sulky.ulid.ULID;

/**
 * ULID 工具类
 * ULID (Universally Unique Lexicographically Sortable Identifier)
 * 26个字符，时间排序，URL安全
 */
public class UniqueIdGenerator {
    
    private static final ULID ulid = new ULID();
    
    /**
     * 生成新的 ULID
     * @return ULID 字符串
     */
    public static String generate() {
        return ulid.nextULID();
    }
    
    /**
     * 验证 ULID 格式
     * @param ulidString ULID 字符串
     * @return 是否有效
     */
    public static boolean isValid(String ulidString) {
        if (ulidString == null || ulidString.length() != 26) {
            return false;
        }
        try {
            ULID.parseULID(ulidString);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}