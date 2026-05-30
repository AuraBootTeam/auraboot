package com.auraboot.framework.connector.airflow.secret.mapper;

import com.auraboot.framework.connector.airflow.secret.ConnectorWebhookSecret;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

@Mapper
public interface ConnectorWebhookSecretMapper extends BaseMapper<ConnectorWebhookSecret> {

    /**
     * Returns the active row plus any inactive rows still within the
     * rotation grace window. Caller (service) decides which to accept based
     * on {@code rotated_at + GRACE_WINDOW > now}.
     */
    @Select("SELECT * FROM connector_webhook_secret "
          + "WHERE connection_name = #{connectionName} "
          + "AND (active = TRUE OR (active = FALSE AND rotated_at IS NOT NULL "
          + "                       AND rotated_at >= #{since})) "
          + "ORDER BY active DESC, rotated_at DESC NULLS LAST")
    List<ConnectorWebhookSecret> findActiveOrGracePeriod(
            @Param("connectionName") String connectionName,
            @Param("since") Instant since);

    @Select("SELECT * FROM connector_webhook_secret "
          + "WHERE connection_name = #{connectionName} AND active = TRUE LIMIT 1")
    ConnectorWebhookSecret findActive(@Param("connectionName") String connectionName);

    /**
     * Atomically demote a row to inactive + stamp rotation. Idempotent — a
     * second call against an already-inactive row returns 0 rows affected.
     */
    @Update("UPDATE connector_webhook_secret "
          + "SET active = FALSE, rotated_at = NOW(), updated_at = NOW() "
          + "WHERE id = #{id} AND active = TRUE")
    int deactivate(@Param("id") Long id);

    @Update("DELETE FROM connector_webhook_secret "
          + "WHERE connection_name = #{connectionName}")
    int deleteByConnection(@Param("connectionName") String connectionName);
}
