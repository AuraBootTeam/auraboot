package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceRepository.AuditEntry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Persists rejected or failed attempts independently from the write transaction. */
@Service
public class AuthoringAuditService {

    private final AuthoringWorkspaceRepository repository;

    public AuthoringAuditService(AuthoringWorkspaceRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordDenied(AuditEntry entry) {
        repository.audit(entry);
    }
}
