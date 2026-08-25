package com.auraboot.framework.plugin.pf4j;

import javax.sql.DataSource;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Assigns opaque process-local identities by actual transaction-resource object identity. */
final class TransactionResourceIdentityRegistry {

    private static final Map<DataSource, String> IDENTITIES = new IdentityHashMap<>();

    private TransactionResourceIdentityRegistry() {
    }

    static synchronized String identityOf(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        return IDENTITIES.computeIfAbsent(
                dataSource, ignored -> "jdbc-resource:" + UUID.randomUUID());
    }
}
