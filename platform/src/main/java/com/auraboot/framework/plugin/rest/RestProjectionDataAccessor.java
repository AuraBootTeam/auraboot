package com.auraboot.framework.plugin.rest;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.plugin.extension.*;
import org.springframework.security.access.AccessDeniedException;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Finite read capability for a trusted route's business projection, never public raw CRUD. */
final class RestProjectionDataAccessor implements DataAccessor {
    private final DataAccessor ordinary;
    private final TenantProjectionAccessor projection;
    private final Set<String> models;
    private final Long tenant=MetaContext.getCurrentTenantId(),user=MetaContext.getCurrentUserId(),member=MetaContext.getCurrentMemberId();
    private final BooleanSupplier authorized;

    RestProjectionDataAccessor(DataAccessor ordinary,TenantProjectionAccessor projection,Set<String> models,BooleanSupplier authorized) {
        this.ordinary=Objects.requireNonNull(ordinary);this.projection=Objects.requireNonNull(projection);
        this.models=Set.copyOf(models);this.authorized=Objects.requireNonNull(authorized);
        verify();
    }
    private void verify() {
        if(tenant==null || tenant<=0 || user==null || user<=0 || member==null || member<=0
                || !MetaContext.exists() || !Objects.equals(tenant,MetaContext.getCurrentTenantId())
                || !Objects.equals(user,MetaContext.getCurrentUserId()) || !Objects.equals(member,MetaContext.getCurrentMemberId())
                || !authorized.getAsBoolean())throw denied();
    }
    public Map<String,Object> getById(String model,String id) {
        verify();return models.contains(model)?projection.getById(model,id):ordinary.getById(model,id);
    }
    public List<Map<String,Object>> query(String model,Map<String,Object> filters) {
        verify();return models.contains(model)?projection.query(model,filters):ordinary.query(model,filters);
    }
    public DataPage queryPage(String model,DataPageQuery query) {
        verify();return models.contains(model)?projection.queryPage(model,query):ordinary.queryPage(model,query);
    }
    public List<Map<String,Object>> queryIn(String model,String field,Collection<?> values) {
        verify();return models.contains(model)?projection.queryIn(model,field,values):ordinary.queryIn(model,field,values);
    }
    public Map<String,Object> create(String model,Map<String,Object> data){throw denied();}
    public Map<String,Object> update(String model,String id,Map<String,Object> data){throw denied();}
    public List<Map<String,Object>> batchCreate(String model,List<Map<String,Object>> data){throw denied();}
    public void delete(String model,String id){throw denied();}
    private static AccessDeniedException denied(){return new AccessDeniedException("Read-only route projection authority is unavailable");}
}
