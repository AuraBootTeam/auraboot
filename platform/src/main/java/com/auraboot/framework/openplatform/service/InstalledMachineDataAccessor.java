package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.plugin.extension.CompareAndSetUpdate;
import com.auraboot.framework.plugin.pf4j.DynamicDataAccessorImpl;
import java.util.*;
import org.springframework.security.access.AccessDeniedException;

/** Mutations consume both the verified command permit and its independent machine contract. */
public class InstalledMachineDataAccessor extends DynamicDataAccessorImpl {
    public InstalledMachineDataAccessor(DynamicDataService data) {super(data);}
    @Override public Map<String,Object> create(String model,Map<String,Object> data) {
        return InstalledMachineAuthority.mutate(model,null,"create",data.keySet(),()->super.create(model,data));
    }
    @Override public Optional<Map<String,Object>> tryCreate(String model,Map<String,Object> data) {
        return InstalledMachineAuthority.mutate(model,null,"create",data.keySet(),()->super.tryCreate(model,data));
    }
    @Override public Map<String,Object> update(String model,String pid,Map<String,Object> data) {
        return InstalledMachineAuthority.mutate(model,pid,"update",data.keySet(),()->super.update(model,pid,data));
    }
    @Override public boolean compareAndSet(String model,String pid,String field,Object expected,Object next) {
        InstalledMachineAuthority.requireCompareField(model,field);
        return InstalledMachineAuthority.mutate(model,pid,"update",Set.of(field),()->super.compareAndSet(model,pid,field,expected,next));
    }
    @Override public boolean compareAndSet(String model,String pid,String field,Object expected,Map<String,Object> next) {
        InstalledMachineAuthority.requireCompareField(model,field);
        return InstalledMachineAuthority.mutate(model,pid,"update",next.keySet(),()->super.compareAndSet(model,pid,field,expected,next));
    }
    @Override public void compareAndSetBatch(String model,String field,List<CompareAndSetUpdate> updates) {
        if(updates==null || updates.size()!=1) throw new AccessDeniedException("Installed CAS requires one verified target");
        InstalledMachineAuthority.requireCompareField(model,field);
        var update=updates.get(0);
        InstalledMachineAuthority.mutate(model,update.recordId(),"update",update.nextValues().keySet(),()->{
            super.compareAndSetBatch(model,field,updates);return null;
        });
    }
    @Override public List<Map<String,Object>> batchCreate(String model,List<Map<String,Object>> rows) {
        Set<String> fields=new HashSet<>();rows.forEach(row->fields.addAll(row.keySet()));
        return InstalledMachineAuthority.mutate(model,null,"create",fields,()->super.batchCreate(model,rows));
    }
    @Override public List<Map<String,Object>> bulkCreate(String model,List<Map<String,Object>> rows) {
        Set<String> fields=new HashSet<>();rows.forEach(row->fields.addAll(row.keySet()));
        return InstalledMachineAuthority.mutate(model,null,"create",fields,()->super.bulkCreate(model,rows));
    }
    @Override public void delete(String model,String pid) {throw new AccessDeniedException("Installed physical deletion is not supported");}
    @Override public void batchDelete(String model,Collection<String> pids) {throw new AccessDeniedException("Installed physical deletion is not supported");}
    @Override public Optional<Long> incrementWithinCap(String model,String pid,String counter,long delta,String cap) {
        throw new AccessDeniedException("Installed counter mutation is not supported");
    }
}
