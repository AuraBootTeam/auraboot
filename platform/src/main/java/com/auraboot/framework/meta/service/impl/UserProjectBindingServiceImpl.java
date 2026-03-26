package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.entity.UserProjectBinding;
import com.auraboot.framework.meta.mapper.UserProjectBindingMapper;
import com.auraboot.framework.meta.service.UserProjectBindingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserProjectBindingServiceImpl implements UserProjectBindingService {

    private final UserProjectBindingMapper bindingMapper;

    @Override
    public void addMember(Long tenantId, Long userId, String projectPid, String bindingRole, Long operatorId) {
        String role = (bindingRole == null || bindingRole.isBlank()) ? "member" : bindingRole;
        bindingMapper.upsertBinding(tenantId, userId, projectPid, role, operatorId);
        log.info("Added user {} to project {} with role {} in tenant {}", userId, projectPid, role, tenantId);
    }

    @Override
    public void removeMember(Long tenantId, Long userId, String projectPid) {
        int deleted = bindingMapper.removeBinding(tenantId, userId, projectPid);
        if (deleted > 0) {
            log.info("Removed user {} from project {} in tenant {}", userId, projectPid, tenantId);
        }
    }

    @Override
    public List<UserProjectBinding> getProjectMembers(Long tenantId, String projectPid) {
        return bindingMapper.findByProjectPid(tenantId, projectPid);
    }

    @Override
    public List<UserProjectBinding> getUserProjects(Long tenantId, Long userId) {
        return bindingMapper.findByUserId(tenantId, userId);
    }

    @Override
    public List<String> getUserProjectPids(Long tenantId, Long userId) {
        return bindingMapper.findProjectPidsByUserId(tenantId, userId);
    }
}
