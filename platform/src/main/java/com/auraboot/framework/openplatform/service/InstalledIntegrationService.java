package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.application.security.ExternalMachineAuthenticator.MachinePrincipal;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.CommandExecuteResult;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.mapper.CommandDefinitionMapper;
import com.auraboot.framework.meta.service.CommandExecutor;
import com.auraboot.framework.openplatform.mapper.*;
import com.auraboot.framework.plugin.extension.integration.*;
import com.auraboot.framework.plugin.extension.integration.ExternalEventIngressExtension.Registration;
import com.auraboot.framework.plugin.pf4j.AuraPluginManager;
import com.auraboot.framework.plugin.mapper.PluginRecordMapper;
import com.fasterxml.jackson.databind.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Installation-bound ingress using only registered commands and the real command pipeline. */
@Service
@RequiredArgsConstructor
public class InstalledIntegrationService {
    private final InstalledIntegrationRegistry registry;
    private final com.auraboot.framework.plugin.pf4j.ExtensionRegistry handlers;
    private final AuraPluginManager plugins;
    private final ApplicationInstallationMapper installations;
    private final ApplicationScopeGrantMapper scopes;
    private final CommandDefinitionMapper commands;
    private final PluginRecordMapper owners;
    private final InstalledIntegrationReceiptMapper receipts;
    private final CommandExecutor executor;
    private final com.auraboot.framework.user.service.UserService users;
    private final com.auraboot.framework.tenant.service.TenantMemberService members;
    private final ObjectMapper json;
    private final OpenPlatformSecretCodec codec;

    record Resolved(Registration registration, String artifact, InstalledIntegrationBinding binding,
                    Map<String,String> configs, Map<String,Object> handlers, String bindingHash, String commandHash, Set<String> completedCommandHashes, String conflictMessageKey) {}
    public record Acceptance(String recordPid, boolean duplicate) {}

    public boolean registered(String source) { return registry.registration(source).isPresent(); }
    public Set<String> eventTypes() {
        Set<String> events = new HashSet<>();
        registry.registrations().forEach(registration -> events.addAll(registration.eventTypes()));
        return Set.copyOf(events);
    }

    private Resolved resolve(MachinePrincipal principal, String source, String eventId, String durableId) {
        var entry = registry.registration(source).orElseThrow(InstalledIntegrationService::denied);
        Registration registration = entry.getKey();
        var installation = installations.findByTenantAndPid(principal.tenantId(), principal.installationPid());
        if (principal.tenantId() == null || principal.tenantId() <= 0 || installation == null
                || !principal.tenantId().equals(installation.getTenantId())
                || !principal.installationPid().equals(installation.getPid())
                || !"active".equals(installation.getStatus()) || installation.getId() == null
                || !principal.scopes().contains(registration.requiredScope())
                || !scopes.findScopes(principal.tenantId(), installation.getId()).contains(registration.requiredScope()))
            throw denied();
        var identity = installations.findActiveIdentity(principal.tenantId(),principal.installationPid());
        if (identity == null || !Objects.equals(identity.applicationPid(),principal.applicationPid())
                || !Objects.equals(identity.installationId(),installation.getId())) throw denied();
        Map<String,String> configs = new LinkedHashMap<>();
        Map<String,Object> approvedHandlers = new LinkedHashMap<>();
        JsonNode common = null;
        for (String code : List.of(registration.acceptCommand(), registration.processCommand(), registration.resultCommand())) {
            CommandDefinition command = commands.findCurrentByCode(code);
            if (command == null || !principal.tenantId().equals(command.getTenantId())
                    || !code.equals(command.getCode()) || !code.substring(0,code.indexOf(':')).equals(command.getModelCode())
                    || !Boolean.TRUE.equals(command.getIsCurrent())
                    || !Boolean.FALSE.equals(command.getDeletedFlag()) || !"published".equals(command.getStatus())
                    || command.getPluginPid() == null || command.getExecutionConfig() == null) throw denied();
            var owner = owners.findByPid(command.getPluginPid());
            if (owner == null || !principal.tenantId().equals(owner.getTenantId())
                    || !registration.commandOwnerPluginId().equals(owner.getPluginId())
                    || !Boolean.FALSE.equals(owner.getDeletedFlag()) || owner.getManifest() == null
                    || (!registration.providerPluginId().equals(owner.getPluginId())
                        && !owner.getManifest().getDependencies().contains(registration.providerPluginId()))) throw denied();
            JsonNode config = parse(command.getExecutionConfig());
            if (!config.path("permissions").equals(json.valueToTree(List.of(registration.commandPermission())))
                    || !config.path("handler").asText().equals(code)
                    || config.path("handlerParams").path("async").asBoolean(false)) throw denied();
            JsonNode declared = config.path("handlerParams").path("installedIntegration");
            if (!declared.isObject() || !(keys(declared).equals(Set.of("sourceCode","capability","bindings"))
                        || keys(declared).equals(Set.of("sourceCode","capability","bindings","conflictMessageKey")))
                    || !source.equals(declared.path("sourceCode").asText())
                    || !registration.capability().equals(declared.path("capability").asText())
                    || !declared.path("bindings").isArray() || declared.path("bindings").size() > 1000) throw denied();
            if (declared.has("conflictMessageKey") && (!declared.get("conflictMessageKey").isTextual()
                    || !declared.get("conflictMessageKey").asText().matches("[a-z][a-zA-Z0-9_.-]{2,159}"))) throw denied();
            if (common != null && !common.equals(declared)) throw denied();
            common = declared;
            var handler = handlers.getCommandHandler(code).orElseThrow(InstalledIntegrationService::denied);
            var artifact = plugins.observeLoadedArtifact(handler.getClass()).orElseThrow(InstalledIntegrationService::denied);
            if (!registration.providerPluginId().equals(artifact.pluginId()) || !entry.getValue().equals(artifact.digest())
                    || !handlers.getSecondaryCommandHandlers(code).isEmpty()) throw denied();
            approvedHandlers.put(code,handler);
            configs.put(code, command.getExecutionConfig());
        }
        JsonNode selected = null;
        Set<String> unique = new HashSet<>();
        for (JsonNode binding : common.path("bindings")) {
            if (!keys(binding).equals(Set.of("installationPid","revision","parameters"))
                    || !binding.path("installationPid").isTextual()
                    || !unique.add(binding.path("installationPid").asText())
                    || !binding.path("revision").isIntegralNumber() || !binding.path("revision").canConvertToLong()
                    || binding.path("revision").asLong() <= 0 || !binding.path("parameters").isObject()) throw denied();
            if (principal.installationPid().equals(binding.path("installationPid").asText())) selected = binding;
        }
        if (selected == null) throw denied();
        @SuppressWarnings("unchecked") Map<String,Object> parameters = json.convertValue(selected.get("parameters"), Map.class);
        if (registration.serverActorParameter() != null) {
            Object actor = parameters.get(registration.serverActorParameter());
            if (!(actor instanceof String pid) || pid.isBlank()) throw denied();
            var user = users.findByPid(pid);
            if (user == null || !pid.equals(user.getPid()) || user.getId() == null || !user.isEnabled()
                    || !user.isAccountNonExpired() || !user.isAccountNonLocked()
                    || !Boolean.FALSE.equals(user.getDeletedFlag())) throw denied();
            var member = members.findByTenantIdAndUserId(principal.tenantId(),user.getId());
            if (member == null || !principal.tenantId().equals(member.getTenantId())
                    || !user.getId().equals(member.getUserId()) || !"active".equals(member.getStatus())
                    || !Boolean.FALSE.equals(member.getDeletedFlag())) throw denied();
        }
        InstalledIntegrationBinding binding = new InstalledIntegrationBinding(principal.tenantId(), principal.applicationPid(),
                principal.installationPid(), source, eventId, durableId, selected.get("revision").asLong(), parameters);
        return new Resolved(registration, entry.getValue(), binding, Map.copyOf(configs), Map.copyOf(approvedHandlers), hash(binding),
                commandHash(registration,configs),
                registry.completedReceiptRegistrations(source).stream()
                        .map(previous -> commandHash(previous,configs)).collect(java.util.stream.Collectors.toUnmodifiableSet()), common.path("conflictMessageKey").asText(null));
    }

    @Transactional
    public Acceptance accept(MachinePrincipal principal, String source, IntegrationEventEnvelope envelope) {
        return inTenant(principal.tenantId(), principal.installationPid(), () -> {
            String external = envelope.headers().get("externalEventId");
            String durable = "ext:" + principal.installationPid() + ":" + source + ":" + external;
            Resolved resolved = resolve(principal, source, external, durable);
            verifyEnvelope(resolved, envelope);
            var r = new InstalledIntegrationReceiptMapper.Receipt(principal.tenantId(), principal.installationPid(),
                    source, external, durable, envelopeHash(envelope), resolved.bindingHash(), resolved.commandHash(),
                    resolved.artifact(), null, "accepting");
            if (receipts.claim(r) == 0) {
                var original = receipts.find(r.tenantId(), r.installationPid(), source, external);
                verifyReceipt(resolved, envelope, original, true);
                if (!Set.of("accepted","completed").contains(original.state()) || original.requestPid() == null) throw denied();
                return new Acceptance(original.requestPid(), true);
            }
            if (envelope.payload().containsKey("requestId") && !external.equals(envelope.payload().get("requestId")))
                throw new IllegalArgumentException("External event id must equal business requestId");
            if (envelope.payload().containsKey("externalSubject"))
                throw new IllegalArgumentException("External subject is host-owned");
            Map<String,Object> payload = new LinkedHashMap<>(envelope.payload());
            payload.put("requestId", external);
            payload.put("externalSubject",Map.of("type",envelope.headers().get("externalSubjectType"),
                    "pid",envelope.headers().get("externalSubjectPid")));
            Map<String,Object> data = execute(resolved, resolved.registration().acceptCommand(), payload, null, false, envelope);
            Object record = data.get("recordPid");
            if (!(record instanceof String pid) || !pid.matches("[A-Za-z0-9_-]{1,100}")
                    || !"accepted".equals(data.get("state"))) throw new IllegalStateException("Invalid native acceptance result");
            if (receipts.accepted(r.tenantId(),r.installationPid(),source,external,pid) != 1) throw denied();
            return new Acceptance(pid,false);
        });
    }

    @Transactional
    public void consume(IntegrationEventEnvelope envelope) {
        String installation = envelope.headers().get("installationPid");
        String source = envelope.headers().get("sourceCode");
        String external = envelope.headers().get("externalEventId");
        inTenant(envelope.tenantId(), installation, () -> {
            var r = receipts.find(envelope.tenantId(),installation,source,external);
            if (r == null) throw denied();
            var candidate = registry.registration(source).orElseThrow(InstalledIntegrationService::denied).getKey();
            MachinePrincipal principal = new MachinePrincipal(envelope.tenantId(), installation, installation,
                    Set.of(candidate.requiredScope()), envelope.headers().get("applicationPid"), installation, null, null);
            Resolved resolved = resolve(principal,source,external,r.durableEventId());
            verifyEnvelope(resolved,envelope); verifyReceipt(resolved,envelope,r,false);
            if ("completed".equals(r.state())) return null;
            if (!"accepted".equals(r.state()) || r.requestPid() == null) throw denied();
            try (var machine = com.auraboot.framework.application.security.MachineDataAccessContext.begin(principal)) {
                execute(resolved,candidate.processCommand(),Map.of("requestPid",r.requestPid()),r.requestPid(),false,envelope);
            }
            if (receipts.completed(r.tenantId(),installation,source,external,r.requestPid()) != 1) throw denied();
            return null;
        });
    }

    @Transactional
    public Map<String,Object> result(MachinePrincipal principal, String source, String eventId) {
        return inTenant(principal.tenantId(),principal.installationPid(), () -> {
            var r = receipts.find(principal.tenantId(),principal.installationPid(),source,eventId);
            if (r == null) throw denied();
            Resolved resolved = resolve(principal,source,eventId,r.durableEventId());
            if (!r.bindingSha256().equals(resolved.bindingHash()) || !commandMatches(resolved,r)
                    || !providerMatches(resolved,r) || !validRequestPid(r.requestPid())) throw denied();
            Map<String,Object> result = new LinkedHashMap<>(execute(resolved,resolved.registration().resultCommand(),Map.of("requestId",eventId),null,true));
            if (!r.requestPid().equals(result.get("recordPid"))) throw denied();
            String delivery = receipts.deliveryStatus(r.tenantId(),r.durableEventId());
            if (delivery == null) delivery = "unavailable";
            if (!Set.of("pending","processing","delivered","failed","unavailable").contains(delivery)) throw denied();
            result.put("deliveryStatus",delivery);
            if ("failed".equals(delivery)) result.put("deliveryFailureCode","integration_delivery_failed");
            return Collections.unmodifiableMap(result);
        });
    }

    private Map<String,Object> execute(Resolved resolved, String code, Map<String,Object> payload, String target, boolean query) {
        return execute(resolved,code,payload,target,query,null);
    }

    private Map<String,Object> execute(Resolved resolved, String code, Map<String,Object> payload, String target,
            boolean query, IntegrationEventEnvelope envelope) {
        CommandExecuteRequest request = new CommandExecuteRequest();
        request.setPayload(payload); request.setTargetRecordPid(target);
        request.setOperationType(query ? "QUERY" : target == null ? "CREATE" : "UPDATE");
        if (code.equals(resolved.registration().processCommand())) {
            JsonNode parameters = parse(resolved.configs().get(code)).path("handlerParams");
            if (target == null || !payload.equals(Map.of("requestPid",target))
                    || parameters.has("targetRecordAction") || parameters.has("targetRecordAuthorization")) throw denied();
            var handler = (com.auraboot.framework.plugin.extension.CommandHandlerExtension) resolved.handlers().get(code);
            @SuppressWarnings("unchecked") Map<String,Object> config = json.convertValue(parse(resolved.configs().get(code)),Map.class);
            if (handler.requiresDslPersistence(code,config,request)) throw denied();
        }
        if (!query) request.setClientRequestId("ii-" + hash(List.of(resolved.binding().durableEventId(),code)).substring(0,48));
        InstalledIntegrationEvent event=envelope==null?null:new InstalledIntegrationEvent(envelope.eventType(),envelope.sequence(),
                envelope.occurredAt(),envelope.headers().get("externalSubjectType"),envelope.headers().get("externalSubjectPid"));
        return InstalledIntegrationExecution.run(code,resolved.configs().get(code),resolved.binding(),query,resolved.handlers().get(code), target, event, () ->
            MetaContext.runWithExternalCommandPermission(resolved.registration().commandPermission(), () -> {
                CommandExecuteResult result;
                try { result = executor.execute(code,request); }
                catch (com.auraboot.framework.exception.BusinessException failure) {
                    if (code.equals(resolved.registration().acceptCommand()) && resolved.conflictMessageKey() != null
                            && failure.getCause() instanceof com.auraboot.framework.plugin.extension.PluginCommandRejectionException rejection
                            && rejection.code() == com.auraboot.framework.plugin.extension.PluginCommandRejectionException.Code.BUSINESS_RULE_BLOCKED
                            && resolved.conflictMessageKey().equals(rejection.messageKey()))
                        throw new ExternalEventIngressService.IdempotencyConflictException();
                    throw failure;
                }
                if (result == null || !"completed".equals(result.getPhaseReached()) || result.getData() == null)
                    throw new IllegalStateException("Installed command did not complete synchronously");
                return result.getData();
            }));
    }

    private void verifyEnvelope(Resolved resolved, IntegrationEventEnvelope event) {
        var binding = resolved.binding();
        if (!"1.0".equals(event.schemaVersion()) || event.tenantId() != binding.tenantId()
                || !resolved.registration().eventTypes().contains(event.eventType())
                || !binding.durableEventId().equals(event.eventId())
                || !("open-platform/" + binding.installationPid() + "/" + binding.sourceCode()).equals(event.source())
                || !binding.installationPid().equals(event.headers().get("installationPid"))
                || !Objects.equals(binding.applicationPid(),event.headers().get("applicationPid"))
                || !binding.sourceCode().equals(event.headers().get("sourceCode"))
                || event.headers().get("externalSubjectType") == null || event.headers().get("externalSubjectPid") == null
                || !(event.headers().get("externalSubjectType")+"/"+event.headers().get("externalSubjectPid")).equals(event.subject())
                || !event.subject().equals(event.orderingKey())) throw denied();
    }
    private void verifyReceipt(Resolved resolved, IntegrationEventEnvelope event, InstalledIntegrationReceiptMapper.Receipt r, boolean replay) {
        if (r != null && !r.envelopeSha256().equals(envelopeHash(event))) {
            if (replay) throw new ExternalEventIngressService.IdempotencyConflictException();
            throw denied();
        }
        if (r == null || !r.bindingSha256().equals(resolved.bindingHash())
                || !commandMatches(resolved,r) || !providerMatches(resolved,r)
                || !r.durableEventId().equals(event.eventId())) throw denied();
    }
    private boolean commandMatches(Resolved resolved, InstalledIntegrationReceiptMapper.Receipt receipt) {
        return receipt.commandSha256().equals(resolved.commandHash())
                || ("completed".equals(receipt.state()) && validRequestPid(receipt.requestPid())
                    && resolved.completedCommandHashes().contains(receipt.commandSha256()));
    }
    private boolean providerMatches(Resolved resolved, InstalledIntegrationReceiptMapper.Receipt receipt) {
        // Terminal replay never invokes old business code; the current result query is PID-bound.
        return receipt.providerSha256().equals(resolved.artifact())
                || ("completed".equals(receipt.state()) && validRequestPid(receipt.requestPid()));
    }
    private boolean validRequestPid(String pid) {
        return pid != null && pid.matches("[A-Za-z0-9_-]{1,100}");
    }
    private <T> T inTenant(Long tenant, String installation, java.util.function.Supplier<T> work) {
        if (tenant == null || tenant <= 0 || installation == null || !installation.matches("[A-Za-z0-9_-]{1,100}")) throw denied();
        java.util.concurrent.atomic.AtomicReference<T> result = new java.util.concurrent.atomic.AtomicReference<>();
        MetaContext.runWithSnapshot(null, () -> {
            MetaContext.setContext(tenant,0L,"installation:"+installation,"installed-integration");
            result.set(work.get());
        });
        return result.get();
    }
    private JsonNode parse(String value) {
        try { return json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw denied(); }
    }
    private Set<String> keys(JsonNode node) {
        Set<String> result = new HashSet<>(); node.fieldNames().forEachRemaining(result::add); return result;
    }
    private String envelopeHash(IntegrationEventEnvelope event) {
        // Request trace/correlation are transport metadata and can change on a valid retry.
        return hash(List.of(event.schemaVersion(),event.eventId(),event.eventType(),event.source(),event.subject(),
                event.occurredAt().toString(),event.tenantId(),event.orderingKey(),event.sequence(),event.payload(),
                Map.of("applicationPid",event.headers().get("applicationPid"),"installationPid",event.headers().get("installationPid"),
                       "sourceCode",event.headers().get("sourceCode"),"externalEventId",event.headers().get("externalEventId"),
                       "externalSubjectType",event.headers().get("externalSubjectType"),"externalSubjectPid",event.headers().get("externalSubjectPid"))));
    }
    String commandHash(Registration registration,Map<String,String> configs) {
        JsonNode tree=json.valueToTree(registration);
        if(!(tree instanceof com.fasterxml.jackson.databind.node.ObjectNode declaration) || !declaration.has("eventTypes"))
            throw new IllegalStateException("Invalid installed integration registration");
        // Sets have no stable JVM iteration order; preserve legacy record layout and sort only this set.
        declaration.set("eventTypes",json.valueToTree(registration.eventTypes().stream().sorted().toList()));
        return hash(Map.of("registration",declaration,"configs",configs));
    }
    String hash(Object value) {
        try { return codec.sha256(json.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true).writeValueAsString(value)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException("Invalid installed integration value"); }
    }
    private static SecurityException denied() { return new SecurityException("Installed integration binding denied"); }
}
