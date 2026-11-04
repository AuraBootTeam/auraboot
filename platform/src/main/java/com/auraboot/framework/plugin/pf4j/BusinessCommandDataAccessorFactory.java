package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.QueryCondition;
import com.auraboot.framework.meta.exception.MetaRecordNotFoundException;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.plugin.extension.*;
import lombok.RequiredArgsConstructor;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Supplier;

/** Host bridge for an exact business root and its owner-authorized resource operations. */
@Component
@RequiredArgsConstructor
public class BusinessCommandDataAccessorFactory {
    private final DynamicDataService data;
    private final MetaModelService models;
    private final SqlSessionFactory sessions;
    private final com.auraboot.framework.meta.mapper.DynamicDataMapper mapper;

    public Scope open(CommandPipelineContext context, CommandHandlerExtension handler) {
        if (!(handler instanceof BusinessResourceAuthorization owner)) throw denied();
        var source = sessions.getConfiguration().getEnvironment().getDataSource();
        Object resource = TransactionSynchronizationManager.getResource(source);
        if (!(resource instanceof ConnectionHolder holder) || !holder.isSynchronizedWithTransaction()) throw denied();
        Connection connection = holder.getConnection();
        try {
            if (connection.getAutoCommit() || connection.isReadOnly() || connection.isClosed()) throw denied();
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot validate business resource transaction", failure);
        }
        Scope scope = new Scope(context, Objects.requireNonNull(owner.newInvocationAuthorization()), source, holder, connection);
        try {
            scope.verify();
            TransactionSynchronizationManager.registerSynchronization(scope.lifetime);
            scope.registered = true;
            scope.initialize();
            scope.verify();
            return scope;
        } catch (RuntimeException failure) {
            scope.reject();
            scope.close();
            throw failure;
        }
    }

    public final class Scope implements AutoCloseable {
        private final CommandPipelineContext context;
        private final CommandPipelineContext.BusinessTargetAuthorization witness;
        private final Object plan, authorization, source;
        private final BusinessResourceAuthorization owner;
        private final ConnectionHolder holder;
        private final Connection connection;
        private final Thread thread = Thread.currentThread();
        private final Long tenant, user, member;
        private final String actor, command, rootModel, rootPid;
        private final Long rootVersion;
        private final TransactionSynchronization lifetime = new TransactionSynchronization() {};
        private final Accessor accessor = new Accessor();
        private Set<String> dependencies, optionalModels, resultFields;
        private record SourceKey(String model, String pid) {}
        private final Map<SourceKey,Long> lockedSources = new HashMap<>();
        private Map<String, Object> root;
        private volatile boolean active = true;
        private boolean registered;
        private volatile boolean poisoned;
        private record Loan(String model, String pid) {}
        private final Deque<Loan> loans = new ArrayDeque<>();
        private int callbacks, operations, rowsRead;

        private Scope(CommandPipelineContext context, BusinessResourceAuthorization owner, Object source,
                      ConnectionHolder holder, Connection connection) {
            this.context = Objects.requireNonNull(context);
            this.owner = owner; this.source = source; this.holder = holder; this.connection = connection;
            witness = context.getBusinessTargetAuthorization();
            plan = context.getPermitPlan(); authorization = context.getAuthorizationVerdict();
            tenant = MetaContext.getCurrentTenantId(); user = MetaContext.getCurrentUserId();
            member = MetaContext.getCurrentMemberId(); actor = MetaContext.getCurrentUserPid();
            command = context.getCommandCode();
            rootModel = context.getCommand() == null ? null : context.getCommand().getModelCode();
            rootPid = context.getRequest() == null ? null : context.getRequest().getTargetRecordId();
            rootVersion = context.getTargetRecordVersion();
        }

        private void verify() {
            if (!active || poisoned || Thread.currentThread() != thread || !MetaContext.exists()
                    || MetaContext.isImpersonating()
                    || com.auraboot.framework.application.security.MachineDataAccessContext.isActive()
                    || com.auraboot.framework.openplatform.service.InstalledIntegrationExecution.active()
                    || com.auraboot.framework.meta.service.impl.AsyncTaskExecutionContext.isActive()
                    || !TransactionSynchronizationManager.isActualTransactionActive()
                    || !TransactionSynchronizationManager.isSynchronizationActive()
                    || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                    || TransactionSynchronizationManager.getResource(source) != holder || holder.getConnection() != connection
                    || registered && !TransactionSynchronizationManager.getSynchronizations().contains(lifetime)
                    || !Objects.equals(tenant, MetaContext.getCurrentTenantId()) || tenant == null || tenant <= 0
                    || !Objects.equals(user, MetaContext.getCurrentUserId()) || user == null || user <= 0
                    || !Objects.equals(member, MetaContext.getCurrentMemberId()) || member == null || member <= 0
                    || !Objects.equals(actor, MetaContext.getCurrentUserPid()) || actor == null || actor.isBlank()
                    || !Objects.equals(command, MetaContext.getAuthorizedCommandCode())
                    || !Objects.equals(tenant, context.getTenantId()) || !Objects.equals(user, context.getUserId())
                    || context.getBusinessTargetAuthorization() != witness || witness == null
                    || !Objects.equals(command, witness.commandCode()) || !Objects.equals(rootModel, witness.modelCode())
                    || !Objects.equals(rootPid, witness.targetRecordId()) || !Objects.equals(member, witness.memberId())
                    || !Objects.equals(tenant, witness.tenantId()) || !Objects.equals(user, witness.userId())
                    || context.getPermitPlan() != plan || context.getPermitPlan() == null || !context.getPermitPlan().isPermitted()
                    || context.getAuthorizationVerdict() != authorization || context.getAuthorizationVerdict() == null
                    || !context.getAuthorizationVerdict().isAuthorized() || !Boolean.TRUE.equals(context.getTargetRecordReadable())
                    || context.getCommand() == null || !Objects.equals(rootModel, context.getCommand().getModelCode())
                    || !Objects.equals(command, context.getCommand().getCode()) || context.getRequest() == null
                    || !Objects.equals(rootPid, context.getRequest().getTargetRecordId())
                    || rootVersion == null || rootVersion < 0
                    || !Objects.equals(rootVersion, context.getTargetRecordVersion())
                    || !permitFrameMatches()) throw denied();
        }

        private boolean permitFrameMatches() {
            Loan loan = loans.peek();
            String model = loan == null ? rootModel : loan.model();
            String pid = loan == null ? rootPid : loan.pid();
            if (loan != null && com.auraboot.framework.meta.service.impl.BusinessResourceDataScope.matchesReadbackPermit(model, pid)) return true;
            return (pid == null ? "ALL" : "TARGET").equals(MetaContext.getCommandPermitScopeFor(model))
                    && Objects.equals(pid, MetaContext.getCommandPermitTargetFor(model));
        }

        private void initialize() {
            BusinessResourcePermissionContract.require(context,owner);
            dependencies = Set.copyOf(owner.businessResourceModels());
            resultFields = Set.copyOf(owner.businessResultFields());
            optionalModels = Set.copyOf(owner.optionalBusinessResourceModels());
            if (!dependencies.containsAll(optionalModels)) throw denied();
            if (dependencies.isEmpty() || dependencies.size() > 32 || !dependencies.contains(rootModel)
                    || resultFields.isEmpty() || resultFields.size() > 128) throw denied();
            dependencies.forEach(BusinessResourceAuthorization::identifier);
            resultFields.forEach(BusinessResourceAuthorization::identifier);
            lockVersion(rootModel, rootPid, rootVersion);
            root = BusinessResourceAuthorization.snapshot(load(rootModel, rootPid));
            if (version(root) != rootVersion) throw new com.auraboot.framework.meta.exception.RecordVersionConflictException(
                    "Business target changed after authorization");
        }

        public DataAccessor accessor() { return accessor; }

        /** Enforce the reviewed result envelope before the transport can serialize it. */
        public Map<String, Object> projectResult(Object result) {
            return operation(() -> {
                if (!(result instanceof Map<?, ?> map) || map.keySet().stream()
                        .anyMatch(key -> !(key instanceof String field) || !resultFields.contains(field))) throw denied();
                Map<String, Object> copy = new LinkedHashMap<>();
                map.forEach((key, value) -> copy.put((String) key, value));
                return BusinessResourceAuthorization.snapshot(copy);
            });
        }

        private void reject() { poisoned = true; holder.setRollbackOnly(); }
        @Override public void close() { active = false; }

        private <T> T operation(Supplier<T> body) {
            try {
                verify();
                if (++operations > 10_000) throw denied();
                T result = body.get();
                verify();
                return result;
            } catch (AccessDeniedException | com.auraboot.framework.exception.PermissionDeniedException | IllegalArgumentException failure) {
                reject();
                throw new DataAccessorException(DataAccessErrorCode.PERMISSION_DENIED, failure);
            } catch (DataAccessorException failure) {
                if (failure.code() == DataAccessErrorCode.PERMISSION_DENIED) reject();
                throw failure;
            }
        }

        private void model(String model) {
            if (!dependencies.contains(model)) throw denied();
            var definition = models.getModelDefinition(model).orElseThrow(BusinessCommandDataAccessorFactory::denied);
            if (!model.equals(definition.getCode()) || definition.getPrimaryKey()!=null && !"pid".equals(definition.getPrimaryKey())
                    || definition.getSourceType() != null && !"physical".equals(definition.getSourceType())
                    || "view".equalsIgnoreCase(definition.getModelType())) throw denied();
        }

        private void lockVersion(String model, String pid, long expected) {
            verify();model(model);
            var definition=models.getModelDefinition(model).orElseThrow(BusinessCommandDataAccessorFactory::denied);
            var key=new SourceKey(model,pid);
            Long locked=lockedSources.get(key);
            if(locked!=null) {
                if(locked!=expected)throw new com.auraboot.framework.meta.exception.RecordVersionConflictException("Business source changed in this invocation");
                return;
            }
            String table=com.auraboot.framework.meta.security.SqlSafetyUtils.requireIdentifier(definition.getTableName(),"business source table");
            var rows=mapper.selectTargetVersionForUpdate(table,"pid",tenant,pid);
            if(rows==null || rows.size()!=1 || !(rows.getFirst().get("row_version") instanceof Number value)
                    || new BigDecimal(value.toString()).longValueExact()!=expected) {
                throw new com.auraboot.framework.meta.exception.RecordVersionConflictException("Business source changed before locking");
            }
            lockedSources.put(key,expected);
        }

        private <T> T nativeCall(BusinessResourceAuthorization.Action action, String model, String pid,
                                 Long expected, Object input, Supplier<T> body) {
            model(model);
            verify();
            return MetaContext.runWithCommandPermitPlan(pid == null ? "ALL" : "TARGET", expected, model, pid, () -> {
                var loan = new Loan(model, pid);
                loans.push(loan);
                try {
                    // Required-reference reads still need their own owner-approved resource operation.
                    return com.auraboot.framework.meta.service.impl.BusinessResourceDataScope.withLoan(
                            action, model, pid, input, this::verify, this::reject, () -> root == null ? body.get()
                            : com.auraboot.framework.meta.service.impl.BusinessResourceReadScope.withReader(
                                    this, accessor, this::verify, body));
                } finally {
                    if (loans.peek() != loan) { reject(); throw denied(); }
                    loans.pop();
                }
            });
        }

        private Map<String, Object> load(String model, String pid) {
            if (pid == null || pid.isBlank() || pid.length() > 128) throw denied();
            Map<String, Object> row = nativeCall(BusinessResourceAuthorization.Action.READ, model, pid, null, null, () -> data.getByIdForAuthorization(model, pid));
            if (row == null || !pid.equals(row.get("pid"))
                    || !tenant.toString().equals(String.valueOf(row.get("tenant_id")))
                    || Boolean.TRUE.equals(row.get("deleted_flag"))) throw denied();
            if (++rowsRead > 10_000) throw denied();
            return BusinessResourceAuthorization.snapshot(row);
        }

        private BusinessResourceAuthorization.Grant authorize(BusinessResourceAuthorization.Request request) {
            var reads = new BusinessResourceSourceReader() {
                private void requireCallback() { verify(); if (callbacks < 1) throw denied(); }
                @Override public Map<String, Object> getById(String model, String pid) {
                    return operation(() -> { requireCallback(); return load(model, pid); });
                }
                @Override public Map<String,Object> getLockedById(String model,String pid) {
                    return operation(()->{requireCallback();var row=load(model,pid);lockVersion(model,pid,version(row));return row;});
                }
                @Override public DataPage queryLocked(String model,DataPageQuery query) {
                    return operation(()->{
                        requireCallback();var page=queryPage(model,query);
                        var ordered=page.records().stream().sorted(Comparator.comparing(row->(String)row.get("pid"))).toList();
                        for(var row:ordered)lockVersion(model,(String)row.get("pid"),version(row));
                        return page;
                    });
                }
                @Override public DataPage queryPage(String model, DataPageQuery query) {
                    return operation(() -> {
                    requireCallback();
                    if (query.page() != 1 || !query.anyOfFilters().isEmpty() || !query.sorts().isEmpty()
                            || !query.keyword().isEmpty()) throw denied();
                    var rows = query(model, query.exactFilters(), query.size());
                    return new DataPage(rows, rows.size(), 1, query.size());
                    });
                }
            };
            callbacks++;
            try {
                var target = new CommandHandlerExtension.TargetAuthorizationContext(command, rootModel, rootPid,
                        witness.action(), tenant, actor, root, reads);
                var grant = owner.authorizeResource(target, request);
                if (grant == null) throw denied();
                verify();
                return grant;
            } catch (RuntimeException failure) {
                throw new AccessDeniedException("Business owner resource contract denied the operation", failure);
            } finally { callbacks--; }
        }

        private List<Map<String, Object>> query(String model, Map<String, Object> filters, int maximum) {
            if (filters == null || filters.isEmpty() || filters.size() > 32) throw denied();
            filters.keySet().forEach(BusinessResourceAuthorization::identifier);
            var conditions = filters.entrySet().stream().map(entry -> QueryCondition.builder()
                    .fieldName(entry.getKey()).operator(entry.getValue()==null?QueryCondition.Operator.IS_NULL:QueryCondition.Operator.EQ).value(entry.getValue()).build()).toList();
            var request = DynamicQueryRequest.builder().pageNum(1).pageSize(maximum).conditions(conditions).build();
            var page = nativeCall(BusinessResourceAuthorization.Action.QUERY, model, null, null, request, () -> data.list(model, request));
            if (page == null || page.getRecords() == null || page.getTotal() == null
                    || page.getTotal() > maximum || page.getTotal() != page.getRecords().size()) throw denied();
            rowsRead += page.getRecords().size();
            if (rowsRead > 10_000) throw denied();
            for (var row : page.getRecords()) {
                if (!tenant.toString().equals(String.valueOf(row.get("tenant_id")))
                        || !(row.get("pid") instanceof String) || Boolean.TRUE.equals(row.get("deleted_flag"))
                        || filters.entrySet().stream().anyMatch(filter -> !equal(row.get(filter.getKey()), filter.getValue()))) throw denied();
            }
            return page.getRecords().stream().map(BusinessResourceAuthorization::snapshot).toList();
        }

        private Map<String, Object> project(Map<String, Object> row, BusinessResourceAuthorization.Grant grant) {
            Map<String, Object> result = new LinkedHashMap<>();
            row.forEach((field, value) -> { if (grant.readableFields().contains(field)) result.put(field, value); });
            return BusinessResourceAuthorization.snapshot(result);
        }

        private void writable(Map<String, Object> changes, BusinessResourceAuthorization.Grant grant) {
            if (changes == null || changes.isEmpty()) throw denied();
            for (String field : changes.keySet()) {
                if ("_expectedVersion".equals(field)) continue;
                if (Set.of("id", "pid", "tenant_id", "row_version", "deleted_flag", "created_at", "created_by", "updated_at", "updated_by").contains(field)
                        || !grant.writableFields().contains(field)) throw denied();
            }
        }

        private final class Accessor implements DataAccessor {
            @Override public Map<String, Object> getById(String model, String pid) {
                return read(model, pid, false);
            }
            @Override public Map<String, Object> getByIdOrNull(String model, String pid) {
                return read(model, pid, true);
            }
            private Map<String, Object> read(String model, String pid, boolean nullable) {
                return operation(() -> {
                    if(!dependencies.contains(model) || pid==null || pid.isBlank() || pid.length()>128)throw denied();
                    if(nullable && optionalModels.contains(model) && models.getModelDefinition(model).isEmpty()) {
                        authorize(new BusinessResourceAuthorization.Request(BusinessResourceAuthorization.Action.READ,model,pid,null,null,null));
                        return null;
                    }
                    Map<String, Object> row = null;
                    try { row = load(model, pid); }
                    catch (MetaRecordNotFoundException absent) { /* CATCH: typed absence still requires owner approval. */ }
                    var grant = authorize(new BusinessResourceAuthorization.Request(BusinessResourceAuthorization.Action.READ,
                            model, pid, row, null, null));
                    if (row == null) {
                        if (nullable) return null;
                        throw new MetaRecordNotFoundException(model, pid);
                    }
                    return project(row, grant);
                });
            }
            @Override public List<Map<String, Object>> query(String model, Map<String, Object> filters) {
                return operation(() -> {
                    model(model);
                    var request = new BusinessResourceAuthorization.Request(BusinessResourceAuthorization.Action.QUERY,
                            model, null, null, null, filters);
                    var grant = authorize(request);
                    if (!grant.readableFields().containsAll(request.filters().keySet())) throw denied();
                    List<Map<String, Object>> result = new ArrayList<>();
                    for (var row : Scope.this.query(model, request.filters(), grant.maximumRows())) {
                        var individual = authorize(new BusinessResourceAuthorization.Request(BusinessResourceAuthorization.Action.READ,
                                model, (String) row.get("pid"), row, null, null));
                        Set<String> intersection = new HashSet<>(individual.readableFields());
                        intersection.retainAll(grant.readableFields());
                        result.add(project(row, new BusinessResourceAuthorization.Grant(intersection, Set.of(), 1)));
                    }
                    return List.copyOf(result);
                });
            }
            @Override public Map<String, Object> create(String model, Map<String, Object> changes) {
                return operation(() -> {
                    model(model);
                    if (rootModel.equals(model) || !"update".equals(witness.action())) throw denied();
                    var request = new BusinessResourceAuthorization.Request(BusinessResourceAuthorization.Action.CREATE,
                            model, null, null, changes, null);
                    var grant = authorize(request); writable(request.changes(), grant);
                    if (request.changes().containsKey("_expectedVersion")) throw denied();
                    var row = nativeCall(BusinessResourceAuthorization.Action.CREATE, model, null, null, request.changes(), () -> data.create(model, new LinkedHashMap<>(request.changes())));
                    if (row == null || !tenant.toString().equals(String.valueOf(row.get("tenant_id")))
                            || !(row.get("pid") instanceof String)) throw denied();
                    return project(row, grant);
                });
            }
            @Override public Map<String, Object> update(String model, String pid, Map<String, Object> changes) {
                return operation(() -> {
                    if (!"update".equals(witness.action()) || rootModel.equals(model) && !rootPid.equals(pid)) throw denied();
                    var row = load(model, pid);
                    var request = new BusinessResourceAuthorization.Request(BusinessResourceAuthorization.Action.UPDATE,
                            model, pid, row, changes, null);
                    // Establish object membership before reporting a version conflict. Value-sensitive
                    // write checks must see a fresh intent, rather than misclassifying a stale retry.
                    authorize(new BusinessResourceAuthorization.Request(BusinessResourceAuthorization.Action.READ,model,pid,row,null,null));
                    long expected = rootModel.equals(model) && rootPid.equals(pid) ? rootVersion : version(row);
                    Object claimed = request.changes().get("_expectedVersion");
                    if (claimed != null && (!(claimed instanceof Number number)
                            || new BigDecimal(number.toString()).longValueExact() != expected)) {
                        throw new com.auraboot.framework.meta.exception.RecordVersionConflictException("Business resource changed concurrently");
                    }
                    var grant=authorize(request);writable(request.changes(),grant);
                    var result = nativeCall(BusinessResourceAuthorization.Action.UPDATE, model, pid, expected, request.changes(),
                            () -> data.update(model, pid, new LinkedHashMap<>(request.changes())));
                    if (result == null || !pid.equals(result.get("pid"))
                            || !tenant.toString().equals(String.valueOf(result.get("tenant_id")))) throw denied();
                    var sourceKey=new SourceKey(model,pid);
                    if(lockedSources.containsKey(sourceKey))lockedSources.put(sourceKey,version(result));
                    return project(result, grant);
                });
            }
            @Override public void delete(String model, String pid) { operation(() -> { throw denied(); }); }
            @Override public List<Map<String, Object>> batchCreate(String model, List<Map<String, Object>> rows) {
                return operation(() -> {
                    if (rows == null || rows.size() > 1_000) throw denied();
                    return rows.stream().map(row -> create(model, row)).toList();
                });
            }
            @Override public DataPage queryPage(String model, DataPageQuery query) {
                return operation(() -> { throw denied(); });
            }
            @Override public boolean compareAndSet(String model, String pid, String field, Object expected, Object next) {
                return operation(() -> { throw denied(); });
            }
            @Override public boolean compareAndSet(String model, String pid, String field, Object expected, Map<String, Object> next) {
                return operation(() -> { throw denied(); });
            }
            @Override public void compareAndSetBatch(String model, String field, List<CompareAndSetUpdate> updates) {
                operation(() -> { throw denied(); });
            }
            @Override public Optional<Long> incrementWithinCap(String model, String pid, String field, long delta, String cap) {
                return operation(() -> { throw denied(); });
            }
        }
    }

    private static long version(Map<String, Object> row) {
        if (!(row.get("row_version") instanceof Number number)) throw denied();
        long version = new BigDecimal(number.toString()).longValueExact();
        if (version < 0) throw denied();
        return version;
    }
    private static boolean equal(Object left, Object right) {
        if (left instanceof Number a && right instanceof Number b) return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString())) == 0;
        return Objects.equals(left, right);
    }
    private static AccessDeniedException denied() { return new AccessDeniedException("Business resource authority is unavailable"); }
}
