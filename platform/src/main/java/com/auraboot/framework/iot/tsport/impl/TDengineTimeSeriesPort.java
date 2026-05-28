package com.auraboot.framework.iot.tsport.impl;

import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.plugin.extension.iot.AggregatedPoint;
import com.auraboot.framework.plugin.extension.iot.QueryParams;
import com.auraboot.framework.plugin.extension.iot.TimeSeriesPoint;
import com.auraboot.framework.plugin.extension.iot.TimeSeriesPort;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reference TDengine 3.x impl of the
 * {@link com.auraboot.framework.plugin.extension.iot.TimeSeriesPort
 * TimeSeriesPort} SPI introduced in M1.A.3.
 *
 * <p><b>Schema convention (M1, multi-tenant single super-table model):</b>
 * <pre>
 *   CREATE STABLE iot_points (
 *     ts   TIMESTAMP,
 *     val  DOUBLE,
 *     qc   BINARY(32)
 *   ) TAGS (
 *     tenant_id   BIGINT,
 *     device_code BINARY(64),
 *     code        BINARY(64)
 *   );
 *   -- one sub-table per (tenantId, deviceCode, code), created lazily by
 *   -- writeBatch via TDengine's `CREATE TABLE ... USING iot_points TAGS (...)`
 *   -- syntax. Tenant lives in the TAG so every SQL touches `WHERE tenant_id = ?`
 *   -- and TDengine's tag index turns it into an O(1) sub-table lookup.
 * </pre>
 *
 * <p><b>Why one STable with three TAGs:</b> the platform target is
 * 30万 – 100万 测点 per tenant — TDengine's sweet spot is &lt; 100M sub-tables
 * per STable, and the three-tag model lets us share retention / compression
 * policy globally while still hard-isolating tenants by tag.
 *
 * <p><b>Catch-Exception discipline (red line §8):</b>
 * <ul>
 *   <li>Spring's {@link DataAccessException} bubbles up unchanged so the
 *       caller's {@code @Transactional} boundary can mark rollback-only and
 *       the rule worker / telemetry consumer can route to its own DLQ.</li>
 *   <li>{@code IllegalArgumentException} from the SPI records (validated in
 *       record canonical constructors) also bubbles unchanged.</li>
 *   <li>Only check connectivity / driver-level setup errors get wrapped in
 *       {@link MetaServiceException} so they surface as a platform-level
 *       startup failure rather than a generic runtime exception.</li>
 * </ul>
 *
 * <p><b>Multi-tenant rigour (red line §1 / §15):</b> every SQL WHERE clause
 * includes {@code tenant_id = ?}. There is no method on this class that omits
 * the tenant predicate; cross-tenant data leak would require corrupting the
 * SPI signature itself.
 *
 * <p>Activated by Spring config {@link TimeSeriesPortConfig}; plugin code
 * uses {@code @Autowired(required = false) TimeSeriesPort port;} and treats
 * {@code null} as &quot;TSDB unavailable&quot; — see the SPI Javadoc.
 *
 * @since 2.6.1
 */
public class TDengineTimeSeriesPort implements TimeSeriesPort {

    private static final Logger log = LoggerFactory.getLogger(TDengineTimeSeriesPort.class);

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    public TDengineTimeSeriesPort(DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /**
     * Ensures the {@code iot_points} STable exists. Idempotent; called by
     * {@link TimeSeriesPortConfig#tdengineTimeSeriesPort(DataSource)} once at
     * bootstrap.
     */
    public void ensureSuperTable() {
        try {
            // TDengine 3.x treats `value` and `quality` as reserved words in
            // unquoted DDL. We use the unambiguous `val` / `qc` (quality
            // code) names instead of relying on cross-driver backtick
            // quoting, which the taos-jdbc REST driver does not pass through
            // reliably. The Java API still exposes `value` / `qualityCode`
            // on the record — only the on-disk schema differs.
            jdbc.execute(
                    "CREATE STABLE IF NOT EXISTS iot_points ("
                            + "ts TIMESTAMP, val DOUBLE, qc BINARY(32)) "
                            + "TAGS (tenant_id BIGINT, device_code BINARY(64), code BINARY(64))");
        } catch (DataAccessException ex) {
            // Surface as a platform-level failure: without the STable nothing
            // works, and silently logging would hide a misconfigured TDengine.
            throw new MetaServiceException(
                    "iot.error.tsport.super_table_init_failed", ex);
        }
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void writeBatch(long tenantId, List<TimeSeriesPoint> points) {
        if (tenantId <= 0) {
            throw new IllegalArgumentException("tenantId must be > 0");
        }
        if (points == null) {
            throw new NullPointerException("points");
        }
        if (points.isEmpty()) {
            return;
        }
        // TDengine: one parameterised stmt per (tenantId, deviceCode, code)
        // sub-table for maximum throughput. JdbcTemplate batchUpdate would
        // issue one stmt per row — acceptable for M1 throughput (10K pts/s
        // per tenant); M2 will switch to taos native pstmt batch API.
        //
        // The taos-jdbc REST driver does NOT correctly bind TAG `?` markers
        // mixed with VALUES `?` markers ("(0xffff):stmt column count not
        // match"), so we inline the TAG literals (they are typed-safe:
        // tenantId is long; deviceCode + code go through escapeSqlString
        // which rejects single-quotes / backslashes and caps length).
        for (TimeSeriesPoint p : points) {
            String subTable = subTableName(tenantId, p.deviceCode(), p.code());
            String safeDevice = escapeSqlString(p.deviceCode(), "deviceCode");
            String safeCode = escapeSqlString(p.code(), "code");
            String sql =
                    "INSERT INTO " + subTable + " USING iot_points TAGS ("
                            + tenantId + ", '" + safeDevice + "', '" + safeCode + "') "
                            + "VALUES (?, ?, ?)";
            jdbc.update(
                    sql,
                    Timestamp.from(p.ts()),
                    p.value().doubleValue(),
                    p.qualityCode());
        }
        if (log.isDebugEnabled()) {
            log.debug("tsport: wrote {} samples tenant={}", points.size(), tenantId);
        }
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<TimeSeriesPoint> queryLatest(
            long tenantId, String deviceCode, List<String> codes, int limit) {
        if (tenantId <= 0) {
            throw new IllegalArgumentException("tenantId must be > 0");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1");
        }
        List<TimeSeriesPoint> out = new ArrayList<>();
        for (String code : codes) {
            // limit == 1 → TDengine's LAST_ROW (O(1) on the index).
            // limit  > 1 → ORDER BY ts DESC LIMIT N.
            String sql;
            if (limit == 1) {
                sql =
                        "SELECT ts, val, qc FROM iot_points "
                                + "WHERE tenant_id = ? AND device_code = ? AND code = ? "
                                + "ORDER BY ts DESC LIMIT 1";
            } else {
                sql =
                        "SELECT ts, val, qc FROM iot_points "
                                + "WHERE tenant_id = ? AND device_code = ? AND code = ? "
                                + "ORDER BY ts DESC LIMIT " + limit;
            }
            final String codeRef = code;
            List<TimeSeriesPoint> rows =
                    jdbc.query(
                            sql,
                            ps -> {
                                ps.setLong(1, tenantId);
                                ps.setString(2, deviceCode);
                                ps.setString(3, code);
                            },
                            (ResultSet rs, int rowNum) -> mapRow(deviceCode, codeRef, rs));
            out.addAll(rows);
        }
        return out;
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<TimeSeriesPoint> queryRange(long tenantId, QueryParams.Range params) {
        if (tenantId <= 0) {
            throw new IllegalArgumentException("tenantId must be > 0");
        }
        if (params.downsample() == null) {
            return queryRangeRaw(tenantId, params);
        }
        return queryRangeDownsampled(tenantId, params);
    }

    private List<TimeSeriesPoint> queryRangeRaw(long tenantId, QueryParams.Range params) {
        List<TimeSeriesPoint> out = new ArrayList<>();
        for (String code : params.codes()) {
            String sql =
                    "SELECT ts, val, qc FROM iot_points "
                            + "WHERE tenant_id = ? AND device_code = ? AND code = ? "
                            + "AND ts >= ? AND ts < ? ORDER BY ts ASC";
            final String codeRef = code;
            List<TimeSeriesPoint> rows =
                    jdbc.query(
                            sql,
                            ps -> bindRangeBase(ps, tenantId, params.deviceCode(), code, params.from(), params.to()),
                            (ResultSet rs, int rowNum) -> mapRow(params.deviceCode(), codeRef, rs));
            out.addAll(rows);
        }
        return out;
    }

    private List<TimeSeriesPoint> queryRangeDownsampled(
            long tenantId, QueryParams.Range params) {
        // Push downsample to TDengine INTERVAL with FILL(LINEAR) for gap-fill.
        long intervalMs = params.downsample().toMillis();
        List<TimeSeriesPoint> out = new ArrayList<>();
        for (String code : params.codes()) {
            String sql =
                    "SELECT _wstart, AVG(val), FIRST(qc) FROM iot_points "
                            + "WHERE tenant_id = ? AND device_code = ? AND code = ? "
                            + "AND ts >= ? AND ts < ? "
                            + "INTERVAL(" + intervalMs + "a) FILL(LINEAR)";
            final String codeRef = code;
            List<TimeSeriesPoint> rows =
                    jdbc.query(
                            sql,
                            ps -> bindRangeBase(ps, tenantId, params.deviceCode(), code, params.from(), params.to()),
                            (ResultSet rs, int rowNum) -> {
                                Instant ts = rs.getTimestamp(1).toInstant();
                                double value = rs.getDouble(2);
                                String quality = rs.getString(3);
                                return new TimeSeriesPoint(
                                        params.deviceCode(), codeRef, ts, value, quality);
                            });
            out.addAll(rows);
        }
        return out;
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<AggregatedPoint> queryAggregate(long tenantId, QueryParams.Aggregate params) {
        if (tenantId <= 0) {
            throw new IllegalArgumentException("tenantId must be > 0");
        }
        String aggFunc = aggregationFunc(params.aggregation());
        long groupByMs = params.groupBy().toMillis();
        List<AggregatedPoint> out = new ArrayList<>();
        for (String code : params.codes()) {
            String sql =
                    "SELECT _wstart, " + aggFunc + "(val), COUNT(*) FROM iot_points "
                            + "WHERE tenant_id = ? AND device_code = ? AND code = ? "
                            + "AND ts >= ? AND ts < ? "
                            + "INTERVAL(" + groupByMs + "a)";
            final String codeRef = code;
            List<AggregatedPoint> rows =
                    jdbc.query(
                            sql,
                            ps -> bindRangeBase(ps, tenantId, params.deviceCode(), code, params.from(), params.to()),
                            (ResultSet rs, int rowNum) -> {
                                Instant bucketStart = rs.getTimestamp(1).toInstant();
                                Number value;
                                if (params.aggregation() == QueryParams.Aggregation.COUNT) {
                                    value = rs.getLong(2);
                                } else {
                                    value = rs.getDouble(2);
                                }
                                long count = rs.getLong(3);
                                return new AggregatedPoint(
                                        params.deviceCode(), codeRef, bucketStart, value, count);
                            });
            out.addAll(rows);
        }
        return out;
    }

    // ---------- helpers ----------

    private static void bindRangeBase(
            PreparedStatement ps,
            long tenantId,
            String deviceCode,
            String code,
            Instant from,
            Instant to)
            throws SQLException {
        ps.setLong(1, tenantId);
        ps.setString(2, deviceCode);
        ps.setString(3, code);
        ps.setTimestamp(4, Timestamp.from(from));
        ps.setTimestamp(5, Timestamp.from(to));
    }

    private static TimeSeriesPoint mapRow(String deviceCode, String code, ResultSet rs)
            throws SQLException {
        Instant ts = rs.getTimestamp(1).toInstant();
        double value = rs.getDouble(2);
        String quality = rs.getString(3);
        return new TimeSeriesPoint(deviceCode, code, ts, value, quality);
    }

    private static String aggregationFunc(QueryParams.Aggregation agg) {
        return switch (agg) {
            case AVG -> "AVG";
            case MIN -> "MIN";
            case MAX -> "MAX";
            case SUM -> "SUM";
            case COUNT -> "COUNT";
            case FIRST -> "FIRST";
            case LAST -> "LAST";
        };
    }

    /**
     * Derives a TDengine-safe sub-table name from
     * {@code (tenantId, deviceCode, code)}. Uses a deterministic
     * non-cryptographic hash to stay under the 192-byte identifier limit
     * regardless of how long device codes get.
     *
     * <p>Sub-table layout (tag-level dedup):
     * <pre>
     *   t_&lt;tenantId&gt;_&lt;base36 hash of (deviceCode|code)&gt;
     * </pre>
     */
    /**
     * Defensive escape for the inline TAG literal in {@code writeBatch}.
     * Rejects strings that contain SQL-string termination characters or
     * exceed the TAG's BINARY(64) bound so a malicious deviceCode / code
     * cannot break out of the literal.
     *
     * <p>This is NOT a general SQL escaper — the only callers are the two
     * TAG slots in the INSERT path. Reads stay parameterised everywhere.
     */
    static String escapeSqlString(String s, String field) {
        if (s == null || s.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be empty");
        }
        if (s.length() > 64) {
            throw new IllegalArgumentException(field + " exceeds 64-byte TAG limit");
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'' || c == '\\' || c == '\0' || c < 0x20) {
                throw new IllegalArgumentException(
                        field + " contains an unsupported character at index " + i);
            }
        }
        return s;
    }

    static String subTableName(long tenantId, String deviceCode, String code) {
        int hash = (deviceCode + "|" + code).hashCode();
        return "t_" + tenantId + "_" + Integer.toUnsignedString(hash, 36);
    }

    /** Exposed for IT setup / teardown only. */
    public DataSource getDataSource() {
        return dataSource;
    }
}
