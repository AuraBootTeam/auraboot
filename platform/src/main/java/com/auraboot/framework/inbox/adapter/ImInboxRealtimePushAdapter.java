package com.auraboot.framework.inbox.adapter;

import com.auraboot.framework.im.dto.WsFrame;
import com.auraboot.framework.im.pubsub.ImRedisPubSub;
import com.auraboot.framework.inbox.model.InboxItem;
import com.auraboot.framework.inbox.service.InboxRealtimePushPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Implements {@link InboxRealtimePushPort} using the IM WebSocket channel
 * (Redis pub/sub → all nodes → connected session).
 * <p>
 * This bean is only active when enterprise-comm is on the classpath.
 *
 * @since 6.4.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImInboxRealtimePushAdapter implements InboxRealtimePushPort {

    private final ImRedisPubSub redisPubSub;

    @Override
    public void pushNewItem(InboxItem item) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", item.getId());
        data.put("itemType", item.getItemType());
        data.put("title", item.getTitle());
        data.put("subtitle", item.getSubtitle());
        data.put("priority", item.getPriority());
        data.put("modelCode", item.getModelCode());
        data.put("recordId", item.getRecordId());
        data.put("deepLink", item.getDeepLink());
        data.put("createdAt", item.getCreatedAt() != null ? item.getCreatedAt().toString() : null);

        WsFrame frame = WsFrame.builder()
                .type("inbox_new")
                .data(data)
                .build();

        redisPubSub.publishToUser(item.getUserId(), frame);
        log.debug("Pushed INBOX_NEW to userId={}", item.getUserId());
    }
}
