package com.auraboot.framework.branding;

import com.auraboot.framework.branding.mapper.AuthAppearanceStateMapper;
import com.auraboot.framework.branding.mapper.AuthAppearanceRevisionMapper;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.exception.ConflictException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AuthAppearanceService {
    private final AuthAppearanceStateMapper stateMapper;
    private final AuthAppearanceRevisionMapper revisionMapper;
    private final DeploymentBrandingProvider brandingProvider;
    private final AuthAppearanceAssetService assetService;

    public record Published(JsonNode appearance, long version) {}
    public record Revision(long version, String action, long actorId, Instant createdAt) {}
    public record View(long version, long publishedVersion, JsonNode draft,
                       JsonNode published, List<Revision> history) {}

    public Published published() {
        if (!enabled()) {
            return new Published(null, 0);
        }
        AuthAppearanceState state = state();
        return new Published(state.getPublished(), state.getPublishedVersion());
    }

    public View view() {
        requireEnabled();
        return toView(state());
    }

    @Transactional
    public View save(long expectedVersion, JsonNode appearance) {
        requireEnabled();
        AuthAppearanceValidator.validate(appearance);
        AuthAppearanceState state = lock(expectedVersion);
        state.setDraft(appearance.deepCopy());
        persist(state, "save", appearance);
        return toView(state);
    }

    @Transactional
    public View publish(long expectedVersion) {
        requireEnabled();
        AuthAppearanceState state = lock(expectedVersion);
        if (state.getDraft() == null) {
            throw new BusinessException("$i18n:auth.appearance.draftRequired");
        }
        AuthAppearanceValidator.validate(state.getDraft());
        validateManagedAssets(state.getDraft());
        state.setPublished(state.getDraft().deepCopy());
        state.setPublishedVersion(state.getVersion() + 1);
        persist(state, "publish", state.getDraft());
        return toView(state);
    }

    @Transactional
    public View rollback(long expectedVersion, long targetVersion) {
        requireEnabled();
        AuthAppearanceState state = lock(expectedVersion);
        AuthAppearanceRevision revision = MetaContext.runWithoutTenantFilter(
                () -> revisionMapper.selectById(targetVersion));
        if (revision == null || "save".equals(revision.getAction())) {
            throw new BusinessException("$i18n:auth.appearance.releaseNotFound");
        }
        AuthAppearanceValidator.validate(revision.getSnapshot());
        validateManagedAssets(revision.getSnapshot());
        state.setDraft(revision.getSnapshot().deepCopy());
        state.setPublished(revision.getSnapshot().deepCopy());
        state.setPublishedVersion(state.getVersion() + 1);
        persist(state, "rollback", revision.getSnapshot());
        return toView(state);
    }

    private void validateManagedAssets(JsonNode appearance) {
        JsonNode images = appearance.path("images");
        if (!images.isObject()) return;
        images.fields().forEachRemaining(field -> {
            String url = field.getValue().asText("");
            String prefix = "/api/auth/appearance/assets/";
            if (field.getKey().endsWith("Url") && url.startsWith(prefix)) {
                try { assetService.read(url.substring(prefix.length())); }
                catch (java.io.IOException exception) {
                    throw new BusinessException("$i18n:auth.appearance.assetMissing");
                }
            }
        });
    }

    private boolean enabled() {
        return brandingProvider.isCommercialBrandingEnabled();
    }

    private void requireEnabled() {
        if (!enabled()) {
            throw new BusinessException("$i18n:auth.appearance.commercialRequired");
        }
    }

    private AuthAppearanceState state() {
        AuthAppearanceState state = MetaContext.runWithoutTenantFilter(() -> stateMapper.selectById(1L));
        if (state == null) {
            throw new IllegalStateException("Deployment appearance state migration is missing");
        }
        return state;
    }

    private AuthAppearanceState lock(long expectedVersion) {
        AuthAppearanceState state = MetaContext.runWithoutTenantFilter(stateMapper::lockState);
        if (state == null) {
            throw new IllegalStateException("Deployment appearance state migration is missing");
        }
        if (state.getVersion() != expectedVersion) {
            throw new ConflictException(ConflictException.ConflictCodes.CAS_VERSION_CONFLICT,
                    "$i18n:auth.appearance.versionConflict", Map.of("currentVersion", state.getVersion()));
        }
        return state;
    }

    private void persist(AuthAppearanceState state, String action, JsonNode snapshot) {
        state.setVersion(state.getVersion() + 1);
        Long actor = MetaContext.getCurrentUserId();
        if (actor == null) {
            throw new IllegalStateException("Appearance mutation requires an authenticated actor");
        }
        AuthAppearanceRevision revision = new AuthAppearanceRevision();
        revision.setVersion(state.getVersion());
        revision.setAction(action);
        revision.setSnapshot(snapshot.deepCopy());
        revision.setActorId(actor);
        revision.setCreatedAt(Instant.now());
        MetaContext.runWithoutTenantFilter(() -> {
            if (stateMapper.updateById(state) != 1 || revisionMapper.insert(revision) != 1) {
                throw new IllegalStateException("Unable to persist deployment appearance revision");
            }
        });
    }

    private View toView(AuthAppearanceState state) {
        List<AuthAppearanceRevision> revisions = MetaContext.runWithoutTenantFilter(
                () -> revisionMapper.selectList(new LambdaQueryWrapper<AuthAppearanceRevision>()
                        .ne(AuthAppearanceRevision::getAction, "save")
                        .orderByDesc(AuthAppearanceRevision::getVersion).last("LIMIT 20")));
        List<Revision> history = revisions.stream().map(revision -> new Revision(
                revision.getVersion(), revision.getAction(), revision.getActorId(), revision.getCreatedAt())).toList();
        return new View(state.getVersion(), state.getPublishedVersion(), state.getDraft(), state.getPublished(), history);
    }
}
