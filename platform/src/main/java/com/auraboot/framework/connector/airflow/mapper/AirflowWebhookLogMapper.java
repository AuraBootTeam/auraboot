package com.auraboot.framework.connector.airflow.mapper;

import com.auraboot.framework.connector.airflow.AirflowWebhookLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;

/**
 * MyBatis-Plus mapper for {@link AirflowWebhookLog}.
 *
 * <p>Placed in the {@code mapper} sub-package (not directly under
 * {@code connector.airflow}) so the wildcard {@code MapperScan} pattern
 * {@code com.auraboot.framework.*.mapper} picks it up automatically — the same
 * convention used by {@code ConnectorOAuthTokenMapper}, {@code SemanticMapper},
 * and every other framework mapper. Adding this package to the explicit
 * {@code @MapperScan} list in {@link com.auraboot.framework.application.MetaApplication}
 * and {@link com.auraboot.framework.application.TestApplication} is still done
 * for clarity; the wildcard alone would cover it.
 *
 * <p>PRD 18 §D observability.
 */
@Mapper
public interface AirflowWebhookLogMapper extends BaseMapper<AirflowWebhookLog> {

    /**
     * Return recent log rows for a given webhook id (typically ≤ 2 rows:
     * the original plus any replay attempt). Used by ops dashboards.
     */
    @Select("SELECT * FROM airflow_webhook_log "
          + "WHERE webhook_id = #{webhookId} "
          + "ORDER BY received_at DESC LIMIT 20")
    List<AirflowWebhookLog> findByWebhookId(@Param("webhookId") String webhookId);

    /**
     * Count REJECTED rows in a time window; used for attack-rate alerting.
     */
    @Select("SELECT COUNT(*) FROM airflow_webhook_log "
          + "WHERE status = 'REJECTED' "
          + "  AND received_at >= #{from} AND received_at < #{to}")
    long countRejectedBetween(@Param("from") Instant from,
                              @Param("to") Instant to);
}
