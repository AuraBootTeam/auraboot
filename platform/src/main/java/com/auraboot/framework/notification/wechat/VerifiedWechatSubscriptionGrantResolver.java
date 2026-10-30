package com.auraboot.framework.notification.wechat;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.dao.entity.AuthIdentity;
import com.auraboot.framework.auth.mapper.AuthIdentityMapper;
import com.auraboot.framework.auth.wechat.WechatMiniProperties;
import com.auraboot.framework.notification.mapper.NotificationMapper;
import com.auraboot.framework.notification.mapper.WechatSubscriptionConsentMapper;
import com.auraboot.framework.notification.mapper.WechatSubscriptionReservationMapper;
import com.auraboot.framework.notification.service.NotificationDeliveryPlanResolver;
import com.auraboot.framework.notification.service.NotificationDeliveryPlanResolver.Plan;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Host-owned bridge from a live authorized source and verified provider consent to one immutable delivery attempt. */
@Service
@ConditionalOnProperty(prefix = "aura.notification.wechat-subscription.reservations", name = "enabled", havingValue = "true")
public class VerifiedWechatSubscriptionGrantResolver implements WechatSubscriptionGrantResolver {
    private static final DateTimeFormatter SOURCE_OCCURRENCE = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT).withZone(ZoneId.of("Asia/Shanghai"));
    private final WechatMiniProperties mini;
    private final Map<String, TemplateRoute> routes;
    private record TemplateRoute(String templateId,
            Map<String, WechatSubscriptionReservationProperties.Value> fields, Map<String, Integer> limits) {}
    private final List<NotificationDeliveryPlanResolver> plans;
    private final NotificationMapper recipients;
    private final AuthIdentityMapper identities;
    private final WechatSubscriptionConsentMapper consent;
    private final WechatSubscriptionReservationMapper reservations;
    private final Clock clock;
    private final TransactionTemplate reads;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Autowired
    public VerifiedWechatSubscriptionGrantResolver(WechatSubscriptionReservationProperties policy,
            WechatSubscriptionProperties delivery, WechatMiniProperties mini,
            List<NotificationDeliveryPlanResolver> plans, NotificationMapper recipients, AuthIdentityMapper identities,
            WechatSubscriptionConsentMapper consent, WechatSubscriptionReservationMapper reservations,
            PlatformTransactionManager manager) {
        this(policy, delivery, mini, plans, recipients, identities, consent, reservations, manager, Clock.systemUTC());
    }
    VerifiedWechatSubscriptionGrantResolver(WechatSubscriptionReservationProperties policy,
            WechatSubscriptionProperties delivery, WechatMiniProperties mini,
            List<NotificationDeliveryPlanResolver> plans, NotificationMapper recipients, AuthIdentityMapper identities,
            WechatSubscriptionConsentMapper consent, WechatSubscriptionReservationMapper reservations,
            PlatformTransactionManager manager, Clock clock) {
        if (blank(mini.getAppId())) throw invalidConfiguration();
        this.routes = configuredRoutes(policy, delivery);
        this.mini = mini; this.plans = List.copyOf(plans);
        this.recipients = recipients; this.identities = identities; this.consent = consent;
        this.reservations = reservations; this.clock = clock;
        reads = new TransactionTemplate(manager);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        // Source resolvers take metadata share locks. PostgreSQL forbids those locks in read-only transactions.
    }

    @Override public Optional<Grant> claim(long tenant, String reference) {
        requireTransaction();
        if (!owned(tenant, reference)) return Optional.empty();
        var context = context(tenant, reference);
        if (context.isEmpty()) return Optional.empty();
        var current = context.get();
        // Same lock order as the authenticated callback. A grant remains global across all tenant memberships.
        consent.lockPrincipal(hash(mini.getAppId() + "\n" + current.identity().getOpenid()));
        var existing = reservations.find(tenant, reference);
        if (existing != null) return verified(current, tenant, reference, existing, null);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var available = reservations.available(mini.getAppId(), current.identity().getOpenid(), current.templateId(), now);
        if (available == null) return Optional.empty();
        String reservation = hash(tenant + "\n" + reference + "\n" + available.reference());
        Grant grant = new Grant(tenant, reference, current.plan().recipientUserPid(), mini.getAppId(),
                current.identity().getPid(), current.identity().getOpenid(), current.templateId(),
                available.reference(), reservation, available.expiresAt(), current.values(), now);
        int inserted = reservations.append(tenant, reference, reservation, available.reference(),
                current.plan().configurationVersion(), encode(grant), now);
        if (inserted != 1) throw new IllegalStateException("WECHAT_RESERVATION_INSERT_FAILED");
        return Optional.of(grant);
    }

    @Override public Optional<Grant> verifyReserved(long tenant, String reference, String reservation) {
        if (!owned(tenant, reference) || blank(reservation)) return Optional.empty();
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            requireTransaction();
            return verify(tenant, reference, reservation);
        }
        // Dispatch happens outside a transaction. Re-read eligibility with its metadata locks in a short consistent view.
        return Objects.requireNonNull(reads.execute(status -> verify(tenant, reference, reservation)));
    }
    private Optional<Grant> verify(long tenant, String reference, String reservation) {
        var current = context(tenant, reference);
        if (current.isEmpty()) return Optional.empty();
        var stored = reservations.find(tenant, reference);
        return stored == null ? Optional.empty() : verified(current.get(), tenant, reference, stored, reservation);
    }
    private Optional<Grant> verified(Context current, long tenant, String reference,
            WechatSubscriptionReservationMapper.Reserved stored, String reservation) {
        Grant grant = decode(stored.snapshot());
        if (grant.tenantId() != tenant || !reference.equals(grant.resolutionReference())
                || !current.plan().configurationVersion().equals(stored.sourceVersion())
                || !current.plan().recipientUserPid().equals(grant.userPid())
                || !mini.getAppId().equals(grant.appId()) || !current.templateId().equals(grant.templateId())
                || !current.identity().getPid().equals(grant.identityPid())
                || !current.identity().getOpenid().equals(grant.openid()) || !current.values().equals(grant.values())
                || (reservation != null && !reservation.equals(grant.reservationReference()))
                || grant.reservedAt() == null || grant.reservedAt().isAfter(clock.instant())
                || grant.expiresAt() == null || !grant.expiresAt().isAfter(clock.instant())) return Optional.empty();
        return reservations.eligible(grant.grantReference(), grant.appId(), grant.openid(), grant.templateId(),
                grant.expiresAt(), clock.instant()) == 1 ? Optional.of(grant) : Optional.empty();
    }
    private record Context(Plan plan, AuthIdentity identity, Map<String, String> values, String templateId) {}
    private Optional<Context> context(long tenant, String reference) {
        if (plans.stream().anyMatch(plan -> plan.getClass().getClassLoader() != getClass().getClassLoader())) return Optional.empty();
        var resolved = plans.stream().map(plan -> plan.resolve(tenant, reference)).flatMap(Optional::stream).toList();
        if (resolved.size() != 1) return Optional.empty();
        var plan = resolved.getFirst();
        if (plan.tenantId() != tenant || !reference.equals(plan.reference()) || !routes.containsKey(plan.sourceType())
                || blank(plan.recipientUserPid()) || blank(plan.configurationVersion()) || plan.configurationVersion().length() > 128)
            return Optional.empty();
        Long user = recipients.findActiveRecipient(tenant, plan.recipientUserPid());
        if (user == null) return Optional.empty();
        var bound = identities.selectList(new QueryWrapper<AuthIdentity>().eq("user_id", user)
                .eq("provider", "wechat_mini").eq("app_id", mini.getAppId()));
        if (bound.size() != 1) return Optional.empty();
        var identity = bound.getFirst();
        if (!Objects.equals(user, identity.getUserId()) || !"wechat_mini".equals(identity.getProvider())
                || !mini.getAppId().equals(identity.getAppId()) || blank(identity.getPid()) || blank(identity.getOpenid())) return Optional.empty();
        var route = routes.get(plan.sourceType());
        Map<String, String> values = new TreeMap<>();
        for (var field : route.fields().entrySet()) {
            String value = switch (field.getValue()) {
                case TITLE -> plan.title(); case CONTENT -> plan.content(); case SOURCE_ID -> plan.sourceId();
                // Notification occurrence is an immutable source fact, never the worker's current clock.
                case OCCURRED_AT -> plan.occurredAt() == null ? null : SOURCE_OCCURRENCE.format(plan.occurredAt());
            };
            Integer max = route.limits().get(field.getKey());
            if (blank(value) || max == null || value.codePointCount(0, value.length()) > max) return Optional.empty();
            values.put(field.getKey(), value);
        }
        return Optional.of(new Context(plan, identity, Map.copyOf(values), route.templateId()));
    }
    private static Map<String, TemplateRoute> configuredRoutes(WechatSubscriptionReservationProperties policy,
            WechatSubscriptionProperties delivery) {
        var contracts = delivery.templateContracts();
        if (!policy.isEnabled() || policy.getSourceTypes().isEmpty() || policy.getSourceTypes().size() > 100
                || policy.getSourceTypes().stream().anyMatch(value -> !value.matches("[A-Za-z_][A-Za-z0-9_]{0,63}"))
                || contracts.isEmpty()) throw invalidConfiguration();
        var result = new TreeMap<String, TemplateRoute>();
        if (delivery.getTemplates().isEmpty()) {
            if (!policy.getRoutes().isEmpty()) throw invalidConfiguration();
            var fields = policy.getFieldSources();
            var limits = contracts.get(delivery.getTemplateId());
            if (!fields.keySet().equals(limits.keySet()) || fields.values().stream().anyMatch(Objects::isNull))
                throw invalidConfiguration();
            for (var source : policy.getSourceTypes())
                result.put(source, new TemplateRoute(delivery.getTemplateId(), Map.copyOf(fields), limits));
        } else {
            if (!policy.getFieldSources().isEmpty() || !policy.getRoutes().keySet().equals(policy.getSourceTypes()))
                throw invalidConfiguration();
            Set<String> selected = new HashSet<>();
            for (var entry : policy.getRoutes().entrySet()) {
                var route = entry.getValue();
                if (route == null || route.getTemplateId() == null || !selected.add(route.getTemplateId()))
                    throw invalidConfiguration();
                var limits = contracts.get(route.getTemplateId());
                if (limits == null || !route.getFieldSources().keySet().equals(limits.keySet())
                        || route.getFieldSources().values().stream().anyMatch(Objects::isNull)) throw invalidConfiguration();
                result.put(entry.getKey(), new TemplateRoute(route.getTemplateId(), Map.copyOf(route.getFieldSources()), limits));
            }
            if (!selected.equals(contracts.keySet())) throw invalidConfiguration();
        }
        return Map.copyOf(result);
    }
    private static IllegalArgumentException invalidConfiguration() {
        return new IllegalArgumentException("WECHAT_RESERVATION_CONFIGURATION_INVALID");
    }
    private boolean owned(long tenant, String reference) {
        return tenant > 0 && Objects.equals(MetaContext.getCurrentTenantId(), tenant)
                && !blank(reference) && reference.length() <= 512;
    }
    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !Integer.valueOf(TransactionDefinition.ISOLATION_SERIALIZABLE)
                    .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))
            throw new IllegalStateException("WECHAT_RESERVATION_SERIALIZABLE_TRANSACTION_REQUIRED");
    }
    private String encode(Grant value) {
        try { return json.writeValueAsString(value); }
        // CATCH: explicit serialization validation; fail the owning transaction without retaining private values.
        catch (JsonProcessingException invalid) { throw new IllegalStateException("WECHAT_RESERVATION_ENCODING_FAILED"); }
    }
    private Grant decode(String value) {
        try { return json.readValue(value, Grant.class); }
        // CATCH: explicit immutable-snapshot validation; corruption is never treated as missing consent.
        catch (JsonProcessingException invalid) { throw new IllegalStateException("WECHAT_RESERVATION_SNAPSHOT_INVALID"); }
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        // CATCH: JVM algorithm availability, unrelated to a transactional service invocation.
        catch (java.security.NoSuchAlgorithmException unavailable) { throw new IllegalStateException("SHA-256 unavailable", unavailable); }
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
