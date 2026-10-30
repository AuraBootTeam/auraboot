package com.auraboot.framework.approval;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.plugin.extension.approval.*;
import com.auraboot.framework.plugin.pf4j.AuraPluginManager;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.stream.Stream;

/** Host routing keeps application facts private and rejects ambiguous ownership. */
@Service
@RequiredArgsConstructor
public class ApprovalSubmissionAccessorImpl implements ApprovalSubmissionAccessor {
    private final ObjectProvider<ApprovalSubmissionSource> localSources;
    private final AuraPluginManager plugins;
    private List<ApprovalSubmissionSource> sources() {
        Map<String,ApprovalSubmissionSource> sources=new LinkedHashMap<>();
        Stream.concat(localSources.stream(),plugins.getExtensionsOfType(ApprovalSubmissionSource.class).stream()).forEach(source->{
            var existing=sources.putIfAbsent(source.businessType(),source);
            if(existing!=null && existing.getClass()!=source.getClass())throw new IllegalStateException("Approval business owner collision");
        });
        return List.copyOf(sources.values());
    }
    public boolean supports(String businessType){return sources().stream().anyMatch(s->s.businessType().equals(businessType));}
    public Optional<ApprovalContract.SubmittedApproval> findSubmitted(long tenant,long actor,String submissionId) {
        if(!Objects.equals(MetaContext.getCurrentTenantId(),tenant) || !Objects.equals(MetaContext.getCurrentUserId(),actor))
            throw new SecurityException("Approval lookup requires the trusted tenant and actor");
        List<ApprovalContract.SubmittedApproval> results=sources().stream().map(s->s.findSubmitted(tenant,actor,submissionId))
            .flatMap(Optional::stream).toList();
        if(results.size()>1)throw new IllegalStateException("Approval submission has multiple business owners");
        return results.stream().findFirst();
    }
    public boolean canWithdraw(long tenant,long actor,String submissionId) {
        var binding=findSubmitted(tenant,actor,submissionId);
        return binding.isPresent() && sources().stream().filter(source->source.businessType().equals(binding.get().businessRef().type()))
            .anyMatch(source->source.canWithdraw(tenant,actor,submissionId));
    }
    public boolean canStart(long tenant,long actor,String submissionId) {
        var binding=findSubmitted(tenant,actor,submissionId);
        return binding.isPresent() && sources().stream().filter(source->source.businessType().equals(binding.get().businessRef().type()))
            .anyMatch(source->source.canStart(tenant,actor,submissionId));
    }
    public Optional<ApprovalContract.BusinessPreview> preview(long tenant,long actor,String submissionId) {
        if(!Objects.equals(MetaContext.getCurrentTenantId(),tenant) || !Objects.equals(MetaContext.getCurrentUserId(),actor))
            throw new SecurityException("Approval preview requires the trusted tenant and actor");
        var results=sources().stream().map(source->source.preview(tenant,actor,submissionId)).flatMap(Optional::stream).toList();
        if(results.size()>1) throw new IllegalStateException("Approval preview has multiple business owners");
        return results.stream().findFirst();
    }
    public boolean canExecute(long tenant,long actor,String submissionId) {
        var binding=findSubmitted(tenant,actor,submissionId);
        return binding.isPresent() && sources().stream().filter(source->source.businessType().equals(binding.get().businessRef().type()))
            .anyMatch(source->source.canExecute(tenant,actor,submissionId));
    }
}
