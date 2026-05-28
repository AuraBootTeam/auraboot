package com.auraboot.framework.connector.saas.dingtalk;

import com.auraboot.framework.connector.saas.AbstractSaasConnectorAdapter;
import com.auraboot.framework.connector.saas.ReadCursor;
import com.auraboot.framework.connector.saas.SaasConnectorConfig;
import com.auraboot.framework.connector.sdk.ConnectorDescriptor;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * DingTalk Open Platform connector scaffold (PRD 18 §B.3.2).
 *
 * <p><strong>Status:</strong> SCAFFOLD — {@code discover()} and {@code read()} throw
 * {@link UnsupportedOperationException}. Real implementation lands in a follow-up PR.
 *
 * <h3>Planned implementation (follow-up PR)</h3>
 * <ul>
 *   <li><strong>Auth:</strong> "企业内部应用" (corp-internal app) flow — exchange
 *       {@code appKey} + {@code appSecret} (stored under
 *       {@link SaasConnectorConfig#clientId()} / {@link SaasConnectorConfig#clientSecret()})
 *       at {@code POST https://api.dingtalk.com/v1.0/oauth2/accessToken} for an
 *       {@code accessToken} valid 7200s. {@code corpId} stored in
 *       {@link SaasConnectorConfig#extras()} keyed {@code corpId}.</li>
 *   <li><strong>Token caching:</strong> 7200s TTL — cache per-(corpId, appKey) and refresh
 *       at T-300s. Concurrent calls de-duplicate via single-flight (Caffeine + Future).</li>
 *   <li><strong>Discovery:</strong> DingTalk object set is fixed; scaffold returns 6 streams.</li>
 *   <li><strong>Read:</strong> v1.0/v2.0 REST APIs vary per stream:
 *       <ul>
 *         <li>{@code users}: {@code POST /topapi/v2/user/list} with {@code dept_id} +
 *             {@code cursor} + {@code size};</li>
 *         <li>{@code departments}: {@code POST /topapi/v2/department/listsub} tree walk;</li>
 *         <li>{@code attendance}: {@code POST /topapi/attendance/list} sliding {@code workDateFrom/To};</li>
 *         <li>{@code approvals}: {@code POST /topapi/processinstance/listids};</li>
 *         <li>{@code contacts}: {@code GET /v1.0/contact/empLeaveRecords} for leavers;</li>
 *         <li>{@code im_messages}: {@code /v1.0/im/workRecords} (limited retention).</li>
 *       </ul>
 *       Cursor stored as {@code cursor.customState.dingNextCursor}.</li>
 *   <li><strong>Rate limit:</strong> 20 QPS default per app per API; some APIs (attendance
 *       /process) are 5 QPS. Respect {@code errcode=88} (rate limited) with backoff.</li>
 * </ul>
 *
 * @since 5.3.0
 */
@Component
public class DingTalkConnectorAdapter extends AbstractSaasConnectorAdapter {

    private static final List<String> STREAMS = List.of(
            "users", "departments", "attendance", "approvals", "contacts", "im_messages");

    private static final ConnectorDescriptor DESCRIPTOR = new ConnectorDescriptor(
            "saas-dingtalk",
            "DingTalk Open Platform via OAuth2 + REST (corp-internal app, 7200s token cache)",
            STREAMS);

    @Override
    public ConnectorDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public Map<String, Object> discover(SaasConnectorConfig config) {
        // TODO(follow-up PR): static stream metadata; the only "discovery" call we
        // actually need is /topapi/v2/department/listsub to enumerate dept tree for
        // user-list paging.
        throw new UnsupportedOperationException(
                "NOT_YET_IMPLEMENTED: DingTalk discover() — wire static stream metadata + dept-tree probe");
    }

    @Override
    public Stream<Map<String, Object>> read(SaasConnectorConfig config, String streamName, ReadCursor cursor) {
        // TODO(follow-up PR): acquire accessToken (cached 7200s), dispatch to per-stream
        // endpoint with cursor/size pagination; surface errcode=88 as rate-limit backoff.
        throw new UnsupportedOperationException(
                "NOT_YET_IMPLEMENTED: DingTalk read(" + streamName
                        + ") — wire accessToken cache + per-stream REST endpoint");
    }
}
