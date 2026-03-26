package com.auraboot.framework.common.util;

import java.io.Serializable;
import java.util.function.Function;

// 创建一个函数式接口
@FunctionalInterface
public interface SFunction<T, R> extends Function<T, R>, Serializable {
}

// 工具类
