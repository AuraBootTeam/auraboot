package com.auraboot.framework.common.util;

import java.nio.ByteBuffer;

public abstract  class ByteUtil {

    /**
     * generate by chatgpt @2024.07.14
     * @param bytes
     * @return
     */
    public static byte[] swapEndian(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN);
        long mostSigBits = buffer.getLong();
        long leastSigBits = buffer.getLong();

        buffer = ByteBuffer.allocate(16);
        buffer.order(java.nio.ByteOrder.BIG_ENDIAN);
        buffer.putLong(mostSigBits);
        buffer.putLong(leastSigBits);
        return buffer.array();
    }


}
