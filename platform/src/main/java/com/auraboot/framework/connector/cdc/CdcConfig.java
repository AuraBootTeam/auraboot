package com.auraboot.framework.connector.cdc;

import java.util.List;
import java.util.Map;

/**
 * Immutable configuration passed to {@link AbstractCdcConnectorAdapter#startCdc(CdcConfig)}.
 *
 * <p>Aligns with PRD 18 §B.3.2 ({@code CdcConfig}) and Airbyte CDC primitives. Extra Debezium
 * engine properties go through {@link #additionalProps()} so the SPI does not need to grow
 * a new field per source flavour.
 *
 * @param connectorPid          owning {@code ab_connector.pid}
 * @param sourceType            source flavour, e.g. {@code mysql}, {@code postgres}
 * @param hostname              source DB hostname
 * @param port                  source DB port; may be {@code null} to use driver default
 * @param database              source DB name
 * @param tableIncludes         allow-list of table identifiers; empty list = all tables
 * @param additionalProps       extra props forwarded to the embedded Debezium engine
 * @param heartbeatIntervalMs   heartbeat ping cadence (ms); {@code null} disables heartbeat
 * @since 5.3.0
 */
public record CdcConfig(
        String connectorPid,
        String sourceType,
        String hostname,
        Integer port,
        String database,
        List<String> tableIncludes,
        Map<String, String> additionalProps,
        Long heartbeatIntervalMs
) {
    public CdcConfig {
        if (connectorPid == null || connectorPid.isBlank()) {
            throw new IllegalArgumentException("connectorPid must not be blank");
        }
        if (sourceType == null || sourceType.isBlank()) {
            throw new IllegalArgumentException("sourceType must not be blank");
        }
        tableIncludes = tableIncludes == null ? List.of() : List.copyOf(tableIncludes);
        additionalProps = additionalProps == null ? Map.of() : Map.copyOf(additionalProps);
    }
}
