package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.openplatform.dto.IntegrationTokenDtos.*;
import com.auraboot.framework.openplatform.entity.ApplicationAccessToken;
import com.auraboot.framework.openplatform.mapper.ApplicationAccessTokenMapper;
import com.auraboot.framework.openplatform.mapper.IntegrationTokenMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class IntegrationTokenService {
    private final OpenPlatformManagementService management;
    private final IntegrationTokenMapper tokens;
    private final ApplicationAccessTokenMapper storage;
    private final OpenPlatformSecretCodec secrets;

    @Transactional
    public Secret create(String installationPid, Create request, String key) {
        var installation = authorize(installationPid);
        requireActive(installation);
        requireFreshKey(installation.installationId(), key);
        Instant now = Instant.now();
        return issue(installationPid, installation, request.name(),
                IntegrationTokenPolicy.expiry(request.expirationMode(), request.expiresAt(), now), now, key);
    }

    @Transactional
    public List<View> list(String installationPid) {
        var installation = authorize(installationPid);
        Instant now = Instant.now();
        return tokens.list(tenant(), installation.installationId()).stream().map(token -> view(token, now)).toList();
    }

    @Transactional
    public Rotation rotate(String installationPid, String pid, Rotate request, String key) {
        var installation = authorize(installationPid);
        requireActive(installation);
        requireFreshKey(installation.installationId(), key);
        Instant now = Instant.now();
        var previous = find(installation.installationId(), pid);
        if (!"active".equals(IntegrationTokenPolicy.status(previous, now)))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Token is not rotatable");
        Instant until = IntegrationTokenPolicy.rotationCutoff(now, request.graceMinutes(), previous.getExpiresAt());
        var next = issue(installationPid, installation, previous.getTokenName(),
                IntegrationTokenPolicy.expiry(request.expirationMode(), request.expiresAt(), now), now, key);
        if (tokens.rotate(tenant(), installation.installationId(), pid, now, until, next.metadata().pid()) != 1)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Token changed during rotation");
        return new Rotation(next, pid, until);
    }

    @Transactional
    public void revoke(String installationPid, String pid) {
        var installation = authorize(installationPid);
        find(installation.installationId(), pid);
        tokens.revoke(tenant(), installation.installationId(), pid, Instant.now());
    }

    private IntegrationTokenMapper.InstallationIdentity authorize(String pid) {
        management.authorizeIntegrationTokens(pid);
        var identity = tokens.lockInstallation(tenant(), pid);
        if (identity == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Installation not found");
        return identity;
    }

    private void requireActive(IntegrationTokenMapper.InstallationIdentity identity) {
        if (!"active".equals(identity.installationStatus()) || !"active".equals(identity.applicationStatus()))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Installation not found");
    }

    private void requireFreshKey(Long installationId, String key) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{8,160}")) throw new IllegalArgumentException("Invalid Idempotency-Key");
        if (tokens.countOperation(tenant(), installationId, key) != 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Operation already applied; inspect token list. Secret cannot be retrieved again");
    }

    private ApplicationAccessToken find(Long installationId, String pid) {
        return tokens.list(tenant(), installationId).stream().filter(token -> pid.equals(token.getPid())).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Token not found"));
    }

    private Secret issue(String installationPid, IntegrationTokenMapper.InstallationIdentity installation,
                         String name, Instant expiresAt, Instant now, String key) {
        if (name == null || name.isBlank() || name.length() > 160) throw new IllegalArgumentException("Token name must contain one to 160 characters");
        String raw = secrets.newAccessToken();
        ApplicationAccessToken token = new ApplicationAccessToken();
        token.setPid(UniqueIdGenerator.generate()); token.setTenantId(tenant());
        token.setInstallationId(installation.installationId()); token.setTokenKind("integration");
        token.setTokenName(name.trim()); token.setManagementKey(key); token.setNeverExpires(expiresAt == null);
        token.setTokenHash(secrets.sha256(raw)); token.setScopes("[]"); token.setAudience(OpenPlatformTokenService.AUDIENCE);
        token.setIssuedAt(now); token.setExpiresAt(expiresAt);
        if (storage.insert(token) != 1) throw new IllegalStateException("Integration token was not stored");
        return new Secret(installation.appId(), installationPid, raw, view(token, now));
    }

    private View view(ApplicationAccessToken token, Instant now) {
        return new View(token.getPid(), token.getTokenName(), IntegrationTokenPolicy.status(token, now),
                Boolean.TRUE.equals(token.getNeverExpires()) ? "never" : "fixed", token.getIssuedAt(), token.getExpiresAt(),
                token.getLastUsedAt(), token.getAcceptUntil(), token.getRotatedAt(), token.getReplacedByPid());
    }

    private Long tenant() { return MetaContext.getCurrentTenantId(); }
}
