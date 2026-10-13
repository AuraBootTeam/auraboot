package com.auraboot.framework.notification.sourcebinding;

import com.auraboot.framework.notification.service.NotificationDeliveryPlanResolver;
import com.auraboot.framework.plugin.extension.DataPage;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Host-owned locator resolver. A locator is not an authorization token. */
@Component
public final class RegisteredSourcePlanResolver implements NotificationDeliveryPlanResolver {
    private final HostSourceBindingProperties properties;
    private final SourceBindingReader reader;
    private final ImportedNotificationMetadataValidator validator;
    private final Clock clock;
    private List<SourceBindingTypes.Binding> registered = List.of();
    @Autowired
    RegisteredSourcePlanResolver(HostSourceBindingProperties p, ImportedNotificationMetadataReader r, ImportedNotificationMetadataValidator v) {
        this(p,(SourceBindingReader) r, v);
    }
    RegisteredSourcePlanResolver(HostSourceBindingProperties p, SourceBindingReader r, ImportedNotificationMetadataValidator v) {
        this(p, r, v, Clock.systemUTC());
    }
    RegisteredSourcePlanResolver(HostSourceBindingProperties p, SourceBindingReader r,
                                 ImportedNotificationMetadataValidator v, Clock clock) {
        properties = p;
        reader = r;
        validator = v;
        this.clock = Objects.requireNonNull(clock);
    }
    @PostConstruct
    void initialize() {
        if (!properties.isEnabled()) return;
        Set<String> keys = new HashSet<>();
        for (var b : properties.getBindings()) SourceBindingTypes.require(keys.add(b.tenantId() + ":" + b.code()));
        registered = List.copyOf(properties.getBindings());
    }
    List<SourceBindingTypes.Binding> registeredBindings() {
        return registered;
    }
    List<SourceBindingTypes.Binding> bindings(long tenant) {
        return registered.stream().filter(b -> b.tenantId() == tenant).toList();
    }
    @Override
    public Optional<Plan> resolve(long tenant, String reference) {
        if (registered.isEmpty() || reference == null || !reference.startsWith("ns1.")) return Optional.empty();
        var l = SourceBindingCanonical.locator(reference);
        var binding = bindings(tenant).stream().filter(b -> b.code().equals(l.binding())).findFirst();
        if (binding.isEmpty()) return Optional.empty();
        return plan(binding.get(), l.source()).stream().filter(p -> p.reference().equals(reference)).findFirst();
    }
    void validate(SourceBindingTypes.Binding b) {
        SourceBindingTypes.require(TransactionSynchronizationManager.isActualTransactionActive() && Integer.valueOf(org.springframework.transaction.TransactionDefinition.ISOLATION_SERIALIZABLE).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()));
        validator.validate(b, reader);
    }
    List<Plan> plan(SourceBindingTypes.Binding b, String sourcePid) {
        validate(b);
        Map<String, Object> source = record(b, b.source().entity(), sourcePid);
        String owner = string(source, b.source().ownerField());
        SourceBindingTypes.require(bindings(b.tenantId()).stream().filter(other -> other.source().entity().modelCode().equals(b.source().entity().modelCode()) && matches(source, other.source().entity().equals())).count() == 1);
        Map<String, Object> ownerRow = record(b, b.owner(), owner);
        List<String> targetPids = targets(source.get(b.source().targets().listField()));
        List<Map<String, Object>> targets = new ArrayList<>();
        for (String pid : targetPids) {
            var row = record(b, b.source().targets().entity(), pid);
            SourceBindingTypes.require(owner.equals(string(row, b.source().targets().ownerField())));
            targets.add(row);
        }
        Instant occurred = instant(source.get(b.source().occurredAtField()));
        JsonNode rule = reader.rule(b.tenantId(), b.ruleId());
        checkRule(b, rule);
        JsonNode template = reader.template(b.tenantId(), b.templateCode());
        checkTemplate(b, template);
        List<Long> users = users(rule.path("recipient_field").asText());
        List<String> userPids = new ArrayList<>();
        List<Map<String, Object>> proofs = new ArrayList<>();
        for (long user : users) {
            String userPid = reader.userPid(b.tenantId(), user);
            SourceBindingTypes.pid(userPid);
            userPids.add(userPid);
            Map<String, Object> filters = filters(b.principal().entity().equals());
            SourceBindingTypes.require(!filters.containsKey(b.principal().principalField()) && !filters.containsKey(b.principal().ownerField()));
            filters.put(b.principal().principalField(), userPid);
            // All active links of this user are counted BEFORE checking their owner.
            DataPage page = reader.page(b, b.principal().entity().modelCode(), filters, 1, 2);
            SourceBindingTypes.require(page.page() == 1 && page.size() == 2 && page.total() == 1 && page.records().size() == 1);
            var link = record(b, b.principal().entity(), string(page.records().getFirst(), "pid"));
            SourceBindingTypes.require(userPid.equals(string(link, b.principal().principalField())) && owner.equals(string(link, b.principal().ownerField())));
            var verified = record(b, b.verification().entity(), string(link, b.principal().verificationField()));
            SourceBindingTypes.require(owner.equals(string(verified, b.verification().ownerField())));
            proofs.add(link);
            proofs.add(verified);
        }
        // The host clock is checked after resolving all facts, both for enqueue and delayed consume.
        // Time itself is not part of the immutable envelope configuration hash.
        var deadline = b.source().targets().deadline();
        if (deadline != null) {
            Instant now = clock.instant();
            for (Map<String, Object> row : targets) {
                SourceBindingTypes.require(instant(row.get(deadline.fieldCode())).isAfter(now));
            }
        }
        JsonNode semanticRule = rule.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) semanticRule).remove(List.of("send_count", "last_evaluated_at", "created_at", "updated_at"));
        JsonNode semanticTemplate = template.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) semanticTemplate).remove("created_at");
        String version = SourceBindingCanonical.hash(Map.of("binding", b, "rule", semanticRule, "template", semanticTemplate, "source", semantic(source), "owner", semantic(ownerRow), "targets", targets.stream().map(this::semantic).toList(), "proofs", proofs.stream().map(this::semantic).toList()));
        String title = render(template.path("subject_template").asText(""), b, source, targetPids.size());
        String body = render(template.path("body_template").asText(), b, source, targetPids.size());
        SourceBindingTypes.require(!title.isBlank() && title.length() <= 256 && !body.isBlank());
        List<Plan> plans = new ArrayList<>();
        for (int i = 0; i<users.size(); i++) plans.add(new Plan(b.tenantId(), SourceBindingCanonical.reference(b.code(), sourcePid, users.get(i)), userPids.get(i), version, b.code(), sourcePid, occurred, title, body));
        return List.copyOf(plans);
    }
    private Map<String, Object> semantic(Map<String, Object> row) {
        var result = new TreeMap<>(row);
        for (String k : List.of("created_at", "updated_at", "changed_at", "row_version")) result.remove(k);
        return result;
    }
    private Map<String, Object> record(SourceBindingTypes.Binding b, SourceBindingTypes.Entity entity, String pid) {
        var row = reader.record(b, entity.modelCode(), pid);
        SourceBindingTypes.require(row != null && pid.equals(row.get("pid")) && row.get("tenant_id") instanceof Number n && n.longValue() == b.tenantId() && row instanceof SourceBindingReader.LiveRecord && matches(row, entity.equals()));
        return row;
    }
    static boolean matches(Map<String, Object> row, List<SourceBindingTypes.Exact> equals) {
        return equals.stream().allMatch(e -> Objects.equals(e.value().raw(), row.get(e.fieldCode())));
    }
    static Map<String, Object> filters(List<SourceBindingTypes.Exact> equals) {
        Map<String, Object> result = new HashMap<>();
        equals.forEach(e -> result.put(e.fieldCode(), e.value().raw()));
        return result;
    }
    private Instant instant(Object value) {
        if (value instanceof Instant i) return i;
        if (value instanceof java.time.OffsetDateTime d) return d.toInstant();
        if (value instanceof java.time.ZonedDateTime d) return d.toInstant();
        if (value instanceof java.sql.Timestamp t) return t.toInstant();
        SourceBindingTypes.require(value instanceof String);
        return Instant.parse((String) value);
    }
    private String string(Map<String, Object> row, String field) {
        Object value = row.get(field);
        SourceBindingTypes.require(value instanceof String && !((String) value).isBlank());
        return(String) value;
    }
    private List<String> targets(Object value) {
        JsonNode n = value instanceof String s ? SourceBindingCanonical.parse(s) : SourceBindingCanonical.tree(value);
        SourceBindingTypes.require(n.isArray() && !n.isEmpty() && n.size() <= 500);
        List<String> result = new ArrayList<>();
        for (var x : n) {
            SourceBindingTypes.require(x.isTextual());
            result.add(SourceBindingTypes.pid(x.textValue()));
        }
        SourceBindingTypes.require(result.stream().distinct().count() == result.size());
        return List.copyOf(result);
    }
    static List<Long> users(String value) {
        SourceBindingTypes.require(value != null && value.matches("[1-9][0-9]*(,[1-9][0-9]*){0,499}"));
        try {
            List<Long> list = Arrays.stream(value.split(",")).map(Long::parseLong).toList();
            SourceBindingTypes.require(list.stream().distinct().count() == list.size());
            return list;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("NOTIFICATION_SOURCE_USERS_INVALID");
        }
    }
    private void checkRule(SourceBindingTypes.Binding b, JsonNode r) {
        SourceBindingTypes.require(r.path("id").longValue() == b.ruleId() && r.path("tenant_id").longValue() == b.tenantId() && r.path("enabled").asBoolean(false) && r.has("deleted_flag") && !r.path("deleted_flag").asBoolean(true) && "IN_APP".equals(r.path("action_channel").asText()) && "SPECIFIC_USERS".equals(r.path("recipient_type").asText()) && b.templateCode().equals(r.path("action_template_code").asText()) && b.source().entity().modelCode().equals(r.path("condition_model_code").asText()));
        JsonNode filters = r.path("condition_filter");
        if (filters.isTextual()) filters = SourceBindingCanonical.parse(filters.textValue());
        SourceBindingTypes.require(filters.isArray());
        Map<String, Object> parsed = new HashMap<>();
        for (var f : filters) {
            SourceBindingTypes.require(f.isObject() && f.size() == 3 && "EQ".equals(f.path("operator").asText()) && f.has("fieldName") &&(f.path("value").isTextual() || f.path("value").isBoolean()));
            Object value = f.path("value").isBoolean() ? f.path("value").booleanValue() : f.path("value").textValue();
            SourceBindingTypes.require(parsed.put(f.path("fieldName").asText(), value) == null);
        }
        SourceBindingTypes.require(parsed.equals(filters(b.source().entity().equals())));
        JsonNode trigger = r.path("trigger_config");
        if (trigger.isTextual()) trigger = SourceBindingCanonical.parse(trigger.textValue());
        SourceBindingTypes.require(trigger.isObject());
        String type = r.path("trigger_type").asText();
        if ("EVENT".equals(type)) {
            SourceBindingTypes.require(trigger.size() == 2 && b.source().entity().modelCode().equals(trigger.path("modelCode").asText()) && Set.of("created", "updated", "deleted").contains(trigger.path("event").asText()));
        } else {
            SourceBindingTypes.require("SCHEDULED".equals(type) && trigger.size() == 3 && Set.of("hourly", "daily", "weekly").contains(trigger.path("schedule").asText()) && trigger.path("hour").isIntegralNumber() && trigger.path("hour").intValue() >= 0 && trigger.path("hour").intValue()<24 && trigger.path("minute").isIntegralNumber() && trigger.path("minute").intValue() >= 0 && trigger.path("minute").intValue()<60);
        }
    }
    private void checkTemplate(SourceBindingTypes.Binding b, JsonNode t) {
        SourceBindingTypes.require(t.path("tenant_id").longValue() == b.tenantId() && b.templateCode().equals(t.path("code").asText()) && t.path("enabled").asBoolean(false));
        JsonNode channels = t.path("channels");
        if (channels.isNull() || channels.isMissingNode()) SourceBindingTypes.require("IN_APP".equals(t.path("channel").asText()));
        else {
            if (channels.isTextual()) channels = SourceBindingCanonical.parse(channels.textValue());
            SourceBindingTypes.require(channels.isArray() && channels.size() == 1 && "in_app".equals(channels.get(0).asText()));
        }
    }
    private String render(String template, SourceBindingTypes.Binding b, Map<String, Object> source, int count) {
        SourceBindingTypes.require(template != null && template.length() <= 32768);
        Matcher m = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}").matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String field = b.variableToSourceField().get(m.group(1));
            SourceBindingTypes.require(field != null);
            Object fact = source.get(field);
            String replacement = fact instanceof Instant || fact instanceof java.time.OffsetDateTime || fact instanceof java.time.ZonedDateTime || fact instanceof java.sql.Timestamp ? instant(fact).toString() : string(source, field);
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        SourceBindingTypes.require(out.indexOf("${")<0 && out.indexOf("{{")<0);
        return out.toString();
    }
}
