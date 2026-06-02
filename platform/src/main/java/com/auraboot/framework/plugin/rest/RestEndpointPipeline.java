package com.auraboot.framework.plugin.rest;

import com.auraboot.framework.meta.service.IdempotencyService;
import com.auraboot.framework.meta.service.impl.CommandEffectExecutor;
import com.auraboot.framework.plugin.extension.PluginHttpRequest;
import com.auraboot.framework.plugin.extension.PluginRequestContext;
import com.auraboot.framework.plugin.extension.RestEndpointExtension;
import com.auraboot.framework.plugin.extension.RestRoute;
import com.auraboot.framework.plugin.pf4j.RestEndpointRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.annotation.Observed;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * gamma-2 governed pipeline for plugin REST endpoints. Wraps the previously-bare
 * {@code extension.handle(...)} call in the same governance envelope the command pipeline gives
 * commands (see {@code CommandExecutorImpl} / {@code CompletionPhase}):
 *
 * <ol>
 *   <li><b>Idempotent replay</b> — for {@link RestRoute#idempotent()} routes carrying an
 *       {@code Idempotency-Key} header, a previously-recorded outcome is replayed byte-for-byte
 *       without re-running the handler ({@link IdempotencyService}).</li>
 *   <li><b>JSON-schema pre-validation</b> — {@link RestRoute#requestJsonSchema()} is enforced
 *       against the request body before the handler runs ({@link RestSchemaValidator}).</li>
 *   <li><b>Transaction</b> — the handler + idempotency record run inside a transaction
 *       ({@link TransactionTemplate}, read-only when {@link RestRoute#readOnlyTx()}); any exception
 *       rolls the whole thing back. The handler writes into an in-memory
 *       {@link BufferingPluginHttpResponse} that the dispatcher only flushes after commit, so a
 *       rollback never leaks a partially-written response.</li>
 *   <li><b>Audit fallback</b> — every request (success or failure) lands one
 *       {@code ab_command_audit_log} row, written <em>outside</em> the request transaction so a
 *       failure audit survives the rollback. Mirrors {@code CommandEffectExecutor#saveAuditLog}.</li>
 * </ol>
 *
 * <p>Exceptions are never swallowed (red line #8): a handler failure rolls back, is audited as a
 * failure, and is rethrown for the dispatcher to map to an HTTP status. No {@code REQUIRES_NEW} is
 * used to escape the rollback — the audit write simply runs after the transaction closes.
 */
@Slf4j
@Service
public class RestEndpointPipeline {

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final PlatformTransactionManager txManager;
    private final IdempotencyService idempotencyService;
    private final RestSchemaValidator schemaValidator;
    private final CommandEffectExecutor effectExecutor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RestEndpointPipeline(PlatformTransactionManager txManager,
                                IdempotencyService idempotencyService,
                                RestSchemaValidator schemaValidator,
                                CommandEffectExecutor effectExecutor) {
        this.txManager = txManager;
        this.idempotencyService = idempotencyService;
        this.schemaValidator = schemaValidator;
        this.effectExecutor = effectExecutor;
    }

    @Observed(name = "plugin.rest.execute", contextualName = "plugin-rest-pipeline")
    public BufferingPluginHttpResponse execute(RestEndpointRegistry.Match m,
                                               PluginHttpRequest req,
                                               PluginRequestContext ctx) {
        final RestRoute route = m.route();
        final RestEndpointExtension ext = m.extension();
        final String routeCode = routeCode(ext.namespace(), route);
        final Long tenantId = ctx.tenantId();
        final Long userId = ctx.userId();
        final String idemKey = req.header(IDEMPOTENCY_HEADER);
        final long start = System.currentTimeMillis();

        // 1) Idempotent replay — short-circuit before opening a write transaction.
        if (route.idempotent() && StringUtils.hasText(idemKey)) {
            Map<String, Object> cached = idempotencyService.checkIdempotency(idemKey, tenantId);
            if (cached != null) {
                log.debug("Idempotent replay for {} key={} tenant={}", routeCode, idemKey, tenantId);
                BufferingPluginHttpResponse replay = BufferingPluginHttpResponse.fromOutcomeMap(cached);
                replay.header("X-Idempotent-Replay", "true");
                return replay;
            }
        }

        final String[] phase = {"init"};
        final TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setReadOnly(route.readOnlyTx());

        try {
            BufferingPluginHttpResponse buf = tx.execute(status -> {
                // 2) Schema pre-validation (rolls back if it throws; nothing written yet).
                phase[0] = "schema_validate";
                schemaValidator.validate(req.body(), route.requestJsonSchema());

                // 3) Handler into the in-memory buffer.
                phase[0] = "handler";
                BufferingPluginHttpResponse b = new BufferingPluginHttpResponse();
                try {
                    ext.handle(req, b, ctx);
                } catch (RuntimeException re) {
                    throw re;
                } catch (Exception e) {
                    throw new RuntimeException(e.getMessage(), e);
                }

                // 4) Record idempotency outcome inside the tx (rolled back if the tx later fails).
                if (route.idempotent() && StringUtils.hasText(idemKey)) {
                    idempotencyService.recordOutcome(idemKey, routeCode, bodyAsMap(req), b.toOutcomeMap(), tenantId);
                }
                return b;
            });

            phase[0] = "completed";
            audit(tenantId, routeCode, userId, req, true, null,
                    System.currentTimeMillis() - start, phase[0], Map.of("status", buf.status()));
            return buf;

        } catch (RuntimeException e) {
            audit(tenantId, routeCode, userId, req, false, e.getMessage(),
                    System.currentTimeMillis() - start, phase[0], null);
            throw e;
        }
    }

    private void audit(Long tenantId, String routeCode, Long userId, PluginHttpRequest req,
                       boolean success, String errorMessage, long execTimeMs,
                       String phaseReached, Map<String, Object> result) {
        // Outside the request transaction (this method runs after tx.execute returns or throws),
        // so a failure audit auto-commits even though the business tx rolled back.
        effectExecutor.saveAuditLog(tenantId, routeCode, null, userId,
                bodyAsMap(req), result, success, errorMessage, execTimeMs, phaseReached,
                new LinkedHashMap<>(Map.of("total", execTimeMs)));
    }

    /** Synthetic command code identifying this route in {@code ab_command_audit_log}. */
    private static String routeCode(String namespace, RestRoute route) {
        return "ext:" + namespace + ":" + route.method() + " " + route.pathPattern();
    }

    /** Best-effort parse of the request body into a map for audit / idempotency payload. */
    private Map<String, Object> bodyAsMap(PluginHttpRequest req) {
        byte[] body = req.body();
        if (body == null || body.length == 0) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {});
            return parsed != null ? parsed : Map.of();
        } catch (Exception e) {
            // Non-JSON or non-object body — keep the audit row but don't fail the request over it.
            return Map.of("_rawBytes", body.length);
        }
    }
}
