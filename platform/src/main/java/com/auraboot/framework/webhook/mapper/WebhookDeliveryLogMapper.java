package com.auraboot.framework.webhook.mapper;

import com.auraboot.framework.webhook.entity.WebhookDeliveryLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * Mapper for WebhookDeliveryLog entity.
 *
 * @since 5.1.0
 */
@Mapper
public interface WebhookDeliveryLogMapper extends BaseMapper<WebhookDeliveryLog> {
}
