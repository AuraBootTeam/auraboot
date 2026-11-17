package com.auraboot.framework.permission.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.entity.DataPermissionPolicy;
import com.auraboot.framework.meta.mapper.DataPermissionPolicyMapper;
import com.auraboot.framework.meta.mapper.DataPermissionRoleBindingMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.permission.dto.RoleFieldAccess.*;
import com.auraboot.framework.rbac.entity.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;

/** Compiles the field grid to existing restrictive policies in the role draft transaction. */
@Service
@RequiredArgsConstructor
public class RoleFieldAccessService {
    private final DataPermissionPolicyMapper policies;
    private final DataPermissionRoleBindingMapper bindings;
    private final MetaModelService models;
    private final DataPermissionAuthoringAccess authoring;
    private static final Set<String> SYSTEM_FIELDS=Set.of("id","pid","tenant_id","deleted_flag","created_by","created_at","updated_by","updated_at");

    public List<Model> catalog(Role role,Set<String> candidates) {
        owned(role);
        var managed=policies.findManagedByRole(role.getTenantId(),role.getPid());
        var all=new TreeSet<>(candidates);managed.forEach(p->all.add(p.getModelCode()));
        List<DataPermissionPolicy> shared=new ArrayList<>();
        var managedBindings=new HashSet<String>();
        for(var b:bindings.findByRolePid(role.getTenantId(),role.getPid())){
            var p=policies.findByPid(role.getTenantId(),b.getPolicyPid());
            if(p==null)throw invalid();
            if(p.getManagedRolePid()==null)shared.add(p);
            else {
                if(!role.getPid().equals(p.getManagedRolePid()))throw invalid();
                managedBindings.add(p.getPid());
            }
        }
        if(!managedBindings.equals(managed.stream().map(DataPermissionPolicy::getPid).collect(java.util.stream.Collectors.toSet())))throw invalid();
        List<Model> result=new ArrayList<>();
        for(String code:all){
            if(!code.matches("^[a-zA-Z][a-zA-Z0-9_]*$"))continue;
            var definition=models.getModelDefinition(code);if(definition.isEmpty())continue;
            List<Field> fields=new ArrayList<>();
            for(var field:models.getModelFields(code)) {
                if(!editable(field))continue;
                String access=state(managed.stream().filter(p->code.equals(p.getModelCode())&&field.getCode().equals(p.getFieldCode())).toList());
                var extra=shared.stream().filter(p->Boolean.TRUE.equals(p.getEnabled())&&code.equals(p.getModelCode())&&field.getCode().equals(p.getFieldCode())
                    && Set.of("column","field_write").contains(p.getPolicyType().toLowerCase(Locale.ROOT))).map(DataPermissionPolicy::getName).filter(Objects::nonNull).sorted().toList();
                fields.add(new Field(field.getCode(),label(field.getDisplayName(),field.getName()),access,extra));
            }
            if(!fields.isEmpty())result.add(new Model(code,label(definition.get().getDisplayName(),definition.get().getName()),List.copyOf(fields)));
        }
        return List.copyOf(result);
    }

    public List<Reviewed> preview(Role role,List<Change> changes,Set<String> permittedModels) {
        if(changes==null)return List.of();
        owned(role);authoring.requireWrite();
        if(changes.size()>1000)throw invalid();
        var catalog=catalog(role,permittedModels);var seen=new HashSet<String>();List<Reviewed> result=new ArrayList<>();
        for(var change:changes){
            if(change==null||change.modelCode()==null||change.fieldCode()==null||change.access()==null
                    ||!Set.of("inherit","read_only","hidden").contains(change.access())||!seen.add(change.modelCode()+":"+change.fieldCode()))throw invalid();
            var model=catalog.stream().filter(m->m.code().equals(change.modelCode())).findFirst().orElseThrow(RoleFieldAccessService::invalid);
            var field=model.fields().stream().filter(f->f.code().equals(change.fieldCode())).findFirst().orElseThrow(RoleFieldAccessService::invalid);
            if(model.label()==null||field.label()==null)throw invalid();
            if(!"inherit".equals(change.access())&&!permittedModels.contains(change.modelCode()))throw invalid();
            if(!field.access().equals(change.access()))result.add(new Reviewed(model.code(),field.code(),model.label(),field.label(),field.access(),change.access()));
        }
        return List.copyOf(result);
    }

    public void apply(Role role,List<Reviewed> changes) {
        owned(role);if(!TransactionSynchronizationManager.isActualTransactionActive())throw invalid();
        if(changes.isEmpty())return;
        authoring.requireWrite();
        for(var change:changes){
            for(var p:policies.findManagedByRole(role.getTenantId(),role.getPid())){
                if(!change.modelCode().equals(p.getModelCode())||!change.fieldCode().equals(p.getFieldCode()))continue;
                if(bindings.deleteBinding(role.getTenantId(),p.getPid(),role.getPid())!=1)throw invalid();
                if(policies.deleteByPid(role.getTenantId(),p.getPid())!=1)throw invalid();
            }
            if(!"inherit".equals(change.access()))create(role,change,"field_write",null);
            if("hidden".equals(change.access()))create(role,change,"column","hide");
        }
    }

    public void deleteOwned(Role role) {
        owned(role);if(!TransactionSynchronizationManager.isActualTransactionActive())throw invalid();
        for(var p:policies.findManagedByRole(role.getTenantId(),role.getPid())){
            bindings.deleteByPolicyPid(role.getTenantId(),p.getPid());
            if(policies.deleteByPid(role.getTenantId(),p.getPid())!=1)throw invalid();
        }
    }

    private void create(Role role,Reviewed change,String type,String mask) {
        DataPermissionPolicy p=new DataPermissionPolicy();p.setTenantId(role.getTenantId());p.setPid(UniqueIdGenerator.generate());p.setManagedRolePid(role.getPid());
        p.setName(change.modelLabel()+" · "+change.fieldLabel());p.setModelCode(change.modelCode());p.setFieldCode(change.fieldCode());p.setPolicyType(type);p.setMaskType(mask);p.setEnabled(true);p.setPriority(100);
        if(policies.insert(p)!=1||bindings.insertBinding(role.getTenantId(),p.getPid(),role.getPid())!=1)throw invalid();
    }
    private static String state(List<DataPermissionPolicy> values) {
        if(values.isEmpty())return "inherit";
        boolean write=false,hide=false;
        for(var p:values){
            if(!Boolean.TRUE.equals(p.getEnabled()))throw invalid();
            if("field_write".equals(p.getPolicyType())&&!write)write=true;
            else if("column".equals(p.getPolicyType())&&"hide".equals(p.getMaskType())&&!hide)hide=true;
            else throw invalid();
        }
        if(!write)throw invalid();
        return hide?"hidden":"read_only";
    }
    private static boolean editable(FieldDefinition field){return field.getCode()!=null&&!SYSTEM_FIELDS.contains(field.getCode())&&!Boolean.TRUE.equals(field.getPrimaryKey());}
    private static String label(String primary,String alternate){if(primary!=null&&!primary.isBlank())return primary;if(alternate!=null&&!alternate.isBlank())return alternate;return null;}
    private static void owned(Role role){if(role==null||!Objects.equals(role.getTenantId(),MetaContext.getCurrentTenantId()))throw invalid();}
    private static AccessDeniedException invalid(){return new AccessDeniedException("Role field restriction configuration is invalid or unavailable");}
}
