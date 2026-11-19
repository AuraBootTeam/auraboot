package com.auraboot.framework.plugin.handover;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.plugin.mapper.HandoverPreviewMapper;
import com.auraboot.framework.plugin.mapper.HandoverPreviewItemMapper;
import com.auraboot.framework.plugin.mapper.HandoverInventoryMapper;
import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.lock.DistributedLock;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.CannotSerializeTransactionException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.stereotype.Service;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Reads an MVCC inventory and writes only immutable PREVIEWED receipts. No adoption methods exist. */
@Service
public class HandoverPreviewService {
    @Schema(name="PluginHandoverPreviewRequest", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Request(@NotBlank @Size(max=128) String contractId, @NotBlank @Pattern(regexp="[0-9a-f]{64}") String contractDigest, @NotBlank @Size(max=64) String sourcePluginPid, @NotBlank @Size(max=128) String idempotencyKey) {
        @JsonAnySetter public void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("Unknown handover preview property: " + key);
        }
    }
    @Schema(name="PluginHandoverPreviewItem")
    public record ItemView(String type, String code, String resourcePid, String targetPluginPid,
                           String action, boolean userModified, String ownershipType) {}
    @Schema(name="PluginHandoverPreview")
    public record Preview(String batchPid, String contractId, String contractDigest, String sourcePluginPid,
            String previewDigest, String status, String createdAt, String expiresAt, boolean expired,
            boolean executionAvailable, int plannedMoves, List<ItemView> items, List<String> limits) {}
    @Schema(name="PluginHandoverContractSummary")
    public record ContractView(String contractId, int revision, String digest, String sourcePluginId,
                                List<String> targetPluginIds, List<String> capabilities) {}
    private final ResourceHandoverContracts contracts;
    private final HandoverInventoryMapper inventory;
    private final HandoverPreviewMapper batches;
    private final HandoverPreviewItemMapper items;
    private final UserPermissionService permissions;
    private final DistributedLock locks;
    private final PlatformTransactionManager transactions;

    public HandoverPreviewService(ResourceHandoverContracts contracts, HandoverInventoryMapper inventory,
            HandoverPreviewMapper batches, HandoverPreviewItemMapper items, UserPermissionService permissions,
            DistributedLock locks, PlatformTransactionManager transactions) {
        this.contracts=contracts; this.inventory=inventory; this.batches=batches; this.items=items;
        this.permissions=permissions; this.locks=locks; this.transactions=transactions;
    }
    private long authorize() {
        Long tenant=MetaContext.getCurrentTenantId(), actor=MetaContext.getCurrentUserId();
        if (tenant==null || actor==null || !permissions.hasPermission(actor,"plugin.plugin.manage")
                || !permissions.hasPermission(actor,"sys.plugin.update"))
            throw new AccessDeniedException("Plugin import and uninstall management permissions are required");
        return tenant;
    }
    public List<ContractView> catalog() {
        authorize();
        return contracts.all().stream().map(c->new ContractView(c.contractId(),c.revision(),HandoverJson.digest(c),
                c.source().pluginId(),c.targets().stream().map(ResourceHandoverContract.PackageVersion::pluginId).toList(),c.capabilities())).toList();
    }
    public Preview preview(Request request) {
        long tenant=authorize();
        if (request==null || !text(request.contractId(),128) || !text(request.sourcePluginPid(),64)
                || !text(request.idempotencyKey(),128) || request.contractDigest()==null
                || !request.contractDigest().matches("[0-9a-f]{64}")) bad("Invalid preview request");
        var contract=contracts.require(request.contractId());
        if (!HandoverJson.digest(contract).equals(request.contractDigest())) conflict("Trusted contract revision changed");
        if (TransactionSynchronizationManager.isActualTransactionActive()) conflict("Preview requires its own snapshot transaction");
        String lockKey="plugin-handover-preview:"+tenant+":"+HandoverJson.digest(request.idempotencyKey());
        if (!locks.tryLock(lockKey,30,TimeUnit.SECONDS)) conflict("Preview key is busy; query its existing result before retrying");
        try {
            var tx=new TransactionTemplate(transactions);
            tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            tx.setTimeout(30);
            return tx.execute(status->create(tenant,request,contract));
        } catch (DuplicateKeyException | CannotSerializeTransactionException collision) {
            // Resolve a committed idempotent result in a new snapshot; never retry source reads/writes.
            var lookup=new TransactionTemplate(transactions);lookup.setReadOnly(true);
            return lookup.execute(status->{
                var committed=batches.byKey(tenant,request.idempotencyKey());
                if (committed==null) throw collision;
                if (!HandoverJson.digest(request).equals(committed.getRequestDigest())) conflict("Idempotency key already identifies another preview");
                return view(committed);
            });
        } finally { locks.unlock(lockKey); }
    }
    public Preview get(String batchPid) {
        long tenant=authorize();
        if (!text(batchPid,64)) bad("Invalid preview PID");
        var tx=new TransactionTemplate(transactions); tx.setReadOnly(true);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return tx.execute(status->{
            var batch=batches.byPid(tenant,batchPid);
            if (batch==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Preview not found in this tenant");
            return view(batch);
        });
    }
    public Preview getByKey(String key) {
        long tenant=authorize();if(!text(key,128)) bad("Invalid preview key");
        var tx=new TransactionTemplate(transactions);tx.setReadOnly(true);tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return tx.execute(status->{var batch=batches.byKey(tenant,key);if(batch==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Preview not found in this tenant");return view(batch);});
    }
    private Preview create(long tenant, Request request, ResourceHandoverContract contract) {
        String requestHash=HandoverJson.digest(request);
        var existing=batches.byKey(tenant,request.idempotencyKey());
        if (existing!=null) {
            if (!requestHash.equals(existing.getRequestDigest())) conflict("Idempotency key already identifies another preview");
            return view(existing);
        }
        JsonNode source=one(inventory.plugin(tenant,request.sourcePluginPid()),"Source installation");
        packageIdentity(source,contract.source(),false);
        var receivers=new LinkedHashMap<String,JsonNode>();
        for (var target:contract.targets()) {
            JsonNode installed=one(inventory.pluginByCode(tenant,target.pluginId()),"Receiver installation");
            packageIdentity(installed,target,true); receivers.put(target.pluginId(),installed);
        }
        var tracked=inventory.tracking(tenant,request.sourcePluginPid());
        if (tracked.size()!=contract.resources().size()) conflict("Source tracking inventory is incomplete or contains additional resources");
        var trackingByKey=new HashMap<String,JsonNode>();
        for (var row:tracked) {
            JsonNode value=row.getState();
            String key=value.path("resource_type").asText()+":"+value.path("resource_code").asText();
            if (trackingByKey.putIfAbsent(key,value)!=null) conflict("Duplicate source tracking identity");
        }
        var planned=new ArrayList<HandoverPreviewItem>();
        var liveFields=new HashMap<String,JsonNode>(); var liveDicts=new HashMap<String,JsonNode>();
        for (var resource:contract.resources()) {
            JsonNode tracking=trackingByKey.get(resource.type()+":"+resource.code());
            if (tracking==null) conflict("Missing source resource: "+resource.code());
            if (!tracking.path("user_modified").isBoolean()
                    || !Set.of("shared","plugin_owned","user_claimed").contains(tracking.path("ownership_type").asText()))
                conflict("Unknown resource protection state: "+resource.code());
            var shared=inventory.trackingByCode(tenant,resource.type(),resource.code());
            if (shared.size()!=1 || !shared.getFirst().getState().path("pid").equals(tracking.path("pid")))
                conflict("Ambiguous shared resource ownership: "+resource.code());
            String pid=tracking.path("resource_pid").asText();
            JsonNode live=switch(resource.type()) {
                case "field" -> one(inventory.field(tenant,pid),"Field");
                case "dict" -> one(inventory.dict(tenant,pid),"Dictionary");
                case "model_field_binding" -> one(inventory.binding(tenant,pid),"Binding");
                default -> throw new IllegalStateException("Unvalidated resource type");
            };
            if (!pid.equals(live.path("pid").asText())) conflict("Live/tracking identity mismatch");
            if (!resource.type().equals("model_field_binding")
                    && (!resource.code().equals(live.path("code").asText())
                    || !request.sourcePluginPid().equals(live.path("plugin_pid").asText())))
                conflict("Live resource owner or code differs from source tracking");
            ObjectNode snapshot=HandoverJson.JSON.createObjectNode().set("tracking",tracking);
            snapshot.set("live",live);
            if (resource.type().equals("field")) {
                if (!resource.expectedDataType().equals(live.path("data_type").asText())) conflict("Field type changed: "+resource.code());
                liveFields.put(resource.code(),live);
                snapshot.set("dictionaryBindings",states(inventory.fieldDicts(tenant,live.path("id").longValue())));
            } else if (resource.type().equals("dict")) {
                liveDicts.put(resource.code(),live);
                snapshot.set("dictionaryItems",states(inventory.dictItems(tenant,live.path("id").longValue())));
            } else {
                JsonNode model=one(inventory.model(tenant,live.path("model_id").longValue()),"Binding model");
                JsonNode field=one(inventory.fieldById(tenant,live.path("field_id").longValue()),"Binding field");
                if (!resource.modelCode().equals(model.path("code").asText()) || !resource.fieldCode().equals(field.path("code").asText()))
                    conflict("Binding relationship changed");
                snapshot.set("model",model); snapshot.set("field",field);
            }
            var item=new HandoverPreviewItem(); item.setTenantId(tenant); item.setOrdinal(planned.size());
            item.setResourceType(resource.type()); item.setResourceCode(resource.code()); item.setResourcePid(pid);
            item.setTargetPluginPid(receivers.get(resource.targetPluginId()).path("pid").asText());
            item.setAction("MOVE_OWNER"); item.setSnapshot(snapshot); planned.add(item);
        }
        sameOwnedPids(inventory.ownedFields(tenant,request.sourcePluginPid()),liveFields.values(),"field");
        sameOwnedPids(inventory.ownedDicts(tenant,request.sourcePluginPid()),liveDicts.values(),"dictionary");
        var providers=HandoverJson.JSON.createArrayNode();
        for (var reference:contract.references()) {
            JsonNode provider=one(inventory.pluginByCode(tenant,reference.provider().pluginId()),"Reference provider");
            packageIdentity(provider,reference.provider(),true);
            JsonNode dict=one(inventory.dictByCode(tenant,reference.code()),"Referenced dictionary");
            if (!provider.path("pid").equals(dict.path("plugin_pid"))) conflict("Referenced dictionary has another owner");
            var item=new HandoverPreviewItem(); item.setTenantId(tenant); item.setOrdinal(planned.size());
            item.setResourceType("dict"); item.setResourceCode(reference.code()); item.setResourcePid(dict.path("pid").asText());
            item.setTargetPluginPid(provider.path("pid").asText()); item.setAction("VERIFY_REFERENCE");
            var referenceTracking=one(inventory.trackingByCode(tenant,"dict",reference.code()),"Reference tracking");
            if (!provider.path("pid").equals(referenceTracking.path("plugin_pid"))
                    || !dict.path("pid").equals(referenceTracking.path("resource_pid"))
                    || !referenceTracking.path("user_modified").isBoolean()
                    || !Set.of("shared","plugin_owned","user_claimed").contains(referenceTracking.path("ownership_type").asText()))
                conflict("Reference tracking identity or protection is unknown");
            var snapshot=HandoverJson.JSON.createObjectNode(); snapshot.set("live",dict);snapshot.set("tracking",referenceTracking);
            snapshot.set("dictionaryItems",states(inventory.dictItems(tenant,dict.path("id").longValue())));
            item.setSnapshot(snapshot);planned.add(item);liveDicts.put(reference.code(),dict);
            providers.add(packageSnapshot(provider));
        }
        for (var resource:contract.resources()) {
            if (resource.type().equals("model_field_binding")) {
                var item=planned.stream().filter(p->p.getResourceType().equals(resource.type())&&p.getResourceCode().equals(resource.code())).findFirst().orElseThrow();
                if (!item.getSnapshot().path("field").path("pid").equals(liveFields.get(resource.fieldCode()).path("pid"))) conflict("Binding uses another field version");
            }
            if (resource.dictCode()!=null) {
                var bindings=inventory.fieldDicts(tenant,liveFields.get(resource.code()).path("id").longValue());
                JsonNode dict=liveDicts.get(resource.dictCode());
                if (bindings.size()!=1 || dict==null || !bindings.getFirst().getState().path("dict_id").equals(dict.path("id"))
                        || !resource.dictCode().equals(bindings.getFirst().getState().path("dict_code").asText()))
                    conflict("Field dictionary relationship differs from the contract");
            }
        }
        ObjectNode snapshot=HandoverJson.JSON.createObjectNode();snapshot.set("source",packageSnapshot(source));
        snapshot.put("contractId",contract.contractId());snapshot.put("contractDigest",request.contractDigest());
        snapshot.put("requestDigest",requestHash);
        snapshot.set("receivers",receivers.values().stream().map(HandoverPreviewService::packageSnapshot).collect(
                HandoverJson.JSON::createArrayNode,ArrayNode::add,ArrayNode::addAll));snapshot.set("providers",providers);
        var itemStates=HandoverJson.JSON.createArrayNode(); planned.forEach(item->itemStates.add(itemSnapshot(item)));
        snapshot.set("items",itemStates);
        var batch=new HandoverPreviewBatch(); batch.setPid(UlidGenerator.generate()); batch.setTenantId(tenant);
        batch.setContractId(contract.contractId()); batch.setContractDigest(request.contractDigest());
        batch.setSourcePluginPid(request.sourcePluginPid());batch.setSourcePluginId(contract.source().pluginId());
        batch.setIdempotencyKey(request.idempotencyKey());batch.setRequestDigest(requestHash);batch.setSnapshot(snapshot);
        batch.setPreviewDigest(HandoverJson.digest(snapshot));batch.setStatus("PREVIEWED");
        batch.setCreatedBy(MetaContext.getCurrentUserId());batch.setCreatedAt(Instant.now());batch.setExpiresAt(batch.getCreatedAt().plusSeconds(900));
        if (batches.insert(batch)!=1) throw new IllegalStateException("Preview receipt was not persisted");
        for (var item:planned) { item.setBatchPid(batch.getPid());if(items.insert(item)!=1) throw new IllegalStateException("Preview item was not persisted"); }
        return view(batches.byPid(tenant,batch.getPid()));
    }
    private Preview view(HandoverPreviewBatch batch) {
        var rows=items.byBatch(batch.getTenantId(),batch.getPid());
        if (!"PREVIEWED".equals(batch.getStatus()) || batch.getSnapshot()==null
                || !batch.getContractId().equals(batch.getSnapshot().path("contractId").asText())
                || !batch.getContractDigest().equals(batch.getSnapshot().path("contractDigest").asText())
                || !batch.getRequestDigest().equals(batch.getSnapshot().path("requestDigest").asText())
                || !batch.getRequestDigest().equals(HandoverJson.digest(new Request(batch.getContractId(),batch.getContractDigest(),batch.getSourcePluginPid(),batch.getIdempotencyKey())))
                || !batch.getSourcePluginPid().equals(batch.getSnapshot().path("source").path("pid").asText())
                || !batch.getPreviewDigest().equals(HandoverJson.digest(batch.getSnapshot()))
                || rows.size()!=batch.getSnapshot().path("items").size()) conflict("Incomplete or corrupt preview receipt");
        var result=new ArrayList<ItemView>();
        for (int i=0;i<rows.size();i++) {
            var row=rows.get(i);
            if (row.getOrdinal()!=i || !itemSnapshot(row).equals(batch.getSnapshot().path("items").get(i))) conflict("Preview item integrity mismatch");
            JsonNode tracking=row.getSnapshot().path("tracking");
            result.add(new ItemView(row.getResourceType(),row.getResourceCode(),row.getResourcePid(),row.getTargetPluginPid(),
                    row.getAction(),tracking.path("user_modified").asBoolean(false),tracking.path("ownership_type").asText("external_reference")));
        }
        return new Preview(batch.getPid(),batch.getContractId(),batch.getContractDigest(),batch.getSourcePluginPid(),batch.getPreviewDigest(),
                batch.getStatus(),batch.getCreatedAt().toString(),batch.getExpiresAt().toString(),!batch.getExpiresAt().isAfter(Instant.now()),false,
                (int)rows.stream().filter(r->r.getAction().equals("MOVE_OWNER")).count(),List.copyOf(result),
                List.of("PREVIEW_ONLY_NO_OWNERSHIP_CHANGE","EXECUTION_EXIT_AND_REVERSE_UNAVAILABLE","ARTIFACT_PROVENANCE_AND_SERVING_READINESS_NOT_VERIFIED"));
    }
    private static ObjectNode itemSnapshot(HandoverPreviewItem item) {
        var n=HandoverJson.JSON.createObjectNode();n.put("type",item.getResourceType());n.put("code",item.getResourceCode());
        n.put("resourcePid",item.getResourcePid());n.put("targetPluginPid",item.getTargetPluginPid());n.put("action",item.getAction());
        n.set("snapshot",item.getSnapshot());return n;
    }
    private static ObjectNode packageSnapshot(JsonNode state) {
        var n=HandoverJson.JSON.createObjectNode();
        for(String key:List.of("pid","plugin_id","version","status")) n.set(key,state.path(key));
        n.put("manifestDigest",HandoverJson.digest(state.path("manifest")));return n;
    }
    private static void packageIdentity(JsonNode value,ResourceHandoverContract.PackageVersion expected,boolean enabled) {
        if (!expected.pluginId().equals(value.path("plugin_id").asText()) || !expected.version().equals(value.path("version").asText())
                || !(enabled?Set.of("enabled"):Set.of("installed","enabled","disabled")).contains(value.path("status").asText()))
            conflict("Package identity, version or lifecycle state is not eligible for preview");
    }
    private static void sameOwnedPids(List<HandoverInventoryMapper.Row> actual,Collection<JsonNode> expected,String type) {
        var a=new HashSet<String>();actual.forEach(r->a.add(r.getState().path("pid").asText()));
        var b=new HashSet<String>();expected.forEach(r->b.add(r.path("pid").asText()));
        if (!a.equals(b)) conflict("Untracked or additional source-owned "+type+" versions");
    }
    private static JsonNode one(List<HandoverInventoryMapper.Row> values,String label) {
        if (values.size()!=1) conflict(label+" is missing or ambiguous"); return values.getFirst().getState();
    }
    private static ArrayNode states(List<HandoverInventoryMapper.Row> values) {
        var array=HandoverJson.JSON.createArrayNode();values.forEach(v->array.add(v.getState()));return array;
    }
    private static boolean text(String value,int max) {return value!=null&&!value.isBlank()&&value.length()<=max;}
    private static void conflict(String message) {throw new ResponseStatusException(HttpStatus.CONFLICT,message);}
    private static void bad(String message) {throw new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
}
