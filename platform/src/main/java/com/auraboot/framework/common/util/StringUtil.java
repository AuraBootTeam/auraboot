package com.auraboot.framework.common.util;

import java.util.concurrent.ThreadLocalRandom;

/**
 * @author 高海军 帝奇 Apr 9, 2015 10:00:29 PM
 */
public abstract class StringUtil {

    public static String random(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append((char) (ThreadLocalRandom.current().nextInt(33, 128)));
        }
        return sb.toString();
    }

    public static boolean isBlank( String string) {
        if (isEmpty(string)) {
            return true;
        } else {
            for(int i = 0; i < string.length(); ++i) {
                if (!Character.isWhitespace(string.charAt(i))) {
                    return false;
                }
            }

            return true;
        }
    }

    public static boolean isNotBlank( String string){
        return !isBlank(string);
    }

    public static boolean isEmpty(String string) {
        return string == null || string.isEmpty();
    }

    public static boolean isNotEmpty(String string) {
        return !isEmpty(string);
    }

    public static String truncate(String string, int maxLength) {
        return string.length() > maxLength ? string.substring(0, maxLength) : string;
    }

    public static String truncate(String string, int maxLength, String truncationIndicator) {
        if (truncationIndicator.length() >= maxLength) {
            throw new IllegalArgumentException("maxLength must be greater than length of truncationIndicator");
        } else if (string.length() > maxLength) {
            int remainingLength = maxLength - truncationIndicator.length();
            return string.substring(0, remainingLength) + truncationIndicator;
        } else {
            return string;
        }
    }


}
