package com.auraboot.framework.meta.service.impl;

/** Classifies complete read queries before embedding them in a FROM clause. */
final class NamedQuerySqlSource {
    private NamedQuerySqlSource() { }
    static boolean isQuery(String source) {
        return source.matches("(?is)^(SELECT|WITH)\\b.*");
    }
}
