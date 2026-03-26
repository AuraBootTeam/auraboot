package com.auraboot.framework.meta.aspect;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.annotation.Idempotent;
import com.auraboot.framework.meta.entity.IdempotentKey;
import com.auraboot.framework.meta.exception.IdempotentException;
import com.auraboot.framework.meta.mapper.IdempotentKeyMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

/**
 * AOP aspect that implements declarative idempotency via {@link Idempotent} annotation.
 *
 * <p>Processing flow:
 * <ol>
 *   <li>Resolve idempotent key from header / SpEL / body hash</li>
 *   <li>If no key is resolved, proceed without idempotency check</li>
 *   <li>Try INSERT with ON CONFLICT DO NOTHING (atomic claim)</li>
 *   <li>If INSERT succeeds (new key), execute method and cache result</li>
 *   <li>If INSERT fails (duplicate key), check existing record status:
 *     <ul>
 *       <li>COMPLETED: return cached response</li>
 *       <li>PROCESSING: reject with IdempotentException</li>
 *       <li>EXPIRED: proceed (allow retry)</li>
 *     </ul>
 *   </li>
 *   <li>On method exception, mark key as EXPIRED to allow retry</li>
 * </ol>
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class IdempotentAspect {

    private static final String IDEMPOTENT_KEY_HEADER = "X-Idempotent-Key";

    private final IdempotentKeyMapper idempotentKeyMapper;
    private final ObjectMapper objectMapper;

    private final ExpressionParser spelParser = new SpelExpressionParser();
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    @Around("@annotation(idempotent)")
    public Object aroundIdempotent(ProceedingJoinPoint joinPoint, Idempotent idempotent) throws Throwable {
        // 1. Resolve the idempotent key
        String key = resolveKey(joinPoint, idempotent);
        if (!StringUtils.hasText(key)) {
            // No key provided, skip idempotency check
            return joinPoint.proceed();
        }

        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.exists() ? MetaContext.getCurrentUserId() : null;
        String commandCode = resolveCommandCode(joinPoint);
        String requestHash = idempotent.includeBodyHash() ? computeBodyHash(joinPoint) : null;

        // 2. Build composite key (include hash if configured)
        String compositeKey = requestHash != null ? key + ":" + requestHash : key;

        // 3. Try to claim the key atomically
        IdempotentKey record = new IdempotentKey();
        record.setIdempotentKey(compositeKey);
        record.setTenantId(tenantId);
        record.setCommandCode(commandCode);
        record.setRequestHash(requestHash);
        record.setStatus(IdempotentKey.STATUS_PROCESSING);
        record.setExpiredAt(Instant.now().plusSeconds(idempotent.ttl()));
        record.setCreatedAt(Instant.now());
        record.setCreatedBy(userId);

        int inserted = idempotentKeyMapper.insertIfAbsent(record);

        if (inserted > 0) {
            // Key claimed successfully, execute the method
            return executeAndRecord(joinPoint, compositeKey, tenantId);
        }

        // 4. Key already exists, check its status
        IdempotentKey existing = idempotentKeyMapper.findByKey(compositeKey, tenantId);
        if (existing == null) {
            // Key expired between INSERT and SELECT, proceed normally
            return joinPoint.proceed();
        }

        switch (existing.getStatus()) {
            case IdempotentKey.STATUS_COMPLETED:
                // Return cached response
                log.info("Idempotent replay for key={}, tenant={}", compositeKey, tenantId);
                return deserializeResponse(existing.getResponseData(), joinPoint);

            case IdempotentKey.STATUS_PROCESSING:
                // Another request is still processing
                log.warn("Duplicate request blocked for key={}, tenant={}", compositeKey, tenantId);
                throw new IdempotentException(idempotent.message());

            case IdempotentKey.STATUS_EXPIRED:
                // Previous attempt failed, allow retry
                log.info("Retrying expired idempotent key={}, tenant={}", compositeKey, tenantId);
                return joinPoint.proceed();

            default:
                return joinPoint.proceed();
        }
    }

    // ==================== Private Helpers ====================

    /**
     * Execute the target method and store the result on success,
     * or mark key as EXPIRED on failure.
     */
    private Object executeAndRecord(ProceedingJoinPoint joinPoint, String compositeKey, Long tenantId) throws Throwable {
        try {
            Object result = joinPoint.proceed();

            // Store successful result
            String responseJson = serializeResponse(result);
            idempotentKeyMapper.updateStatusAndResponse(
                    compositeKey, tenantId, IdempotentKey.STATUS_COMPLETED, responseJson);

            log.debug("Idempotent key recorded: key={}, tenant={}", compositeKey, tenantId);
            return result;
        } catch (Throwable ex) {
            // Mark key as EXPIRED so the client can retry
            idempotentKeyMapper.markExpired(compositeKey, tenantId);
            log.warn("Idempotent key marked expired after failure: key={}, error={}", compositeKey, ex.getMessage());
            throw ex;
        }
    }

    /**
     * Resolve the idempotent key from multiple sources (priority order):
     * 1. SpEL expression
     * 2. X-Idempotent-Key header
     * 3. Body hash (if configured and no other key found)
     */
    private String resolveKey(ProceedingJoinPoint joinPoint, Idempotent idempotent) {
        // Priority 1: SpEL expression
        if (StringUtils.hasText(idempotent.keyExpression())) {
            String spelKey = evaluateSpel(joinPoint, idempotent.keyExpression());
            if (StringUtils.hasText(spelKey)) {
                return spelKey;
            }
        }

        // Priority 2: HTTP header
        String headerKey = getHeaderKey();
        if (StringUtils.hasText(headerKey)) {
            return headerKey;
        }

        // Priority 3: Body hash as fallback (only if includeBodyHash is true)
        if (idempotent.includeBodyHash()) {
            return computeBodyHash(joinPoint);
        }

        return null;
    }

    /**
     * Get idempotent key from HTTP request header.
     */
    private String getHeaderKey() {
        try {
            ServletRequestAttributes attributes =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null) {
                HttpServletRequest request = attributes.getRequest();
                return request.getHeader(IDEMPOTENT_KEY_HEADER);
            }
        } catch (Exception e) {
            log.debug("Unable to read HTTP header for idempotent key: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Evaluate SpEL expression against method arguments.
     */
    private String evaluateSpel(ProceedingJoinPoint joinPoint, String expression) {
        try {
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            Method method = signature.getMethod();
            Object[] args = joinPoint.getArgs();

            StandardEvaluationContext context = new StandardEvaluationContext();
            context.setVariable("args", args);

            // Register named parameters
            String[] paramNames = parameterNameDiscoverer.getParameterNames(method);
            if (paramNames != null) {
                for (int i = 0; i < paramNames.length; i++) {
                    context.setVariable(paramNames[i], args[i]);
                    context.setVariable("p" + i, args[i]);
                }
            }

            Object result = spelParser.parseExpression(expression).getValue(context, Object.class);
            return result != null ? result.toString() : null;
        } catch (Exception e) {
            log.warn("Failed to evaluate SpEL expression '{}': {}", expression, e.getMessage());
            return null;
        }
    }

    /**
     * Resolve command code from method arguments (looks for String parameter named commandCode).
     */
    private String resolveCommandCode(ProceedingJoinPoint joinPoint) {
        try {
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            String[] paramNames = parameterNameDiscoverer.getParameterNames(signature.getMethod());
            Object[] args = joinPoint.getArgs();
            if (paramNames != null) {
                for (int i = 0; i < paramNames.length; i++) {
                    if ("commandCode".equals(paramNames[i]) && args[i] instanceof String) {
                        return (String) args[i];
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    /**
     * Compute SHA-256 hash of method arguments (excluding HttpServletRequest/Response).
     */
    private String computeBodyHash(ProceedingJoinPoint joinPoint) {
        try {
            Object[] args = joinPoint.getArgs();
            StringBuilder sb = new StringBuilder();
            for (Object arg : args) {
                if (arg == null) continue;
                // Skip servlet types
                if (arg instanceof HttpServletRequest) continue;
                if (arg instanceof jakarta.servlet.http.HttpServletResponse) continue;
                sb.append(objectMapper.writeValueAsString(arg));
            }
            return sha256(sb.toString());
        } catch (Exception e) {
            log.warn("Failed to compute body hash: {}", e.getMessage());
            return null;
        }
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder(64);
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            log.warn("SHA-256 computation failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Serialize method return value to JSON for caching.
     */
    private String serializeResponse(Object result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.warn("Failed to serialize idempotent response: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Deserialize cached response back to the method's return type.
     */
    private Object deserializeResponse(String json, ProceedingJoinPoint joinPoint) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            Class<?> returnType = signature.getReturnType();
            return objectMapper.readValue(json, returnType);
        } catch (Exception e) {
            log.warn("Failed to deserialize cached response, re-executing: {}", e.getMessage());
            try {
                return joinPoint.proceed();
            } catch (Throwable t) {
                throw new RuntimeException("Idempotent replay failed", t);
            }
        }
    }
}
