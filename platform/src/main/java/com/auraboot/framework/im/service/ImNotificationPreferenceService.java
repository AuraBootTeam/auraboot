package com.auraboot.framework.im.service;

import com.auraboot.framework.im.mapper.ImNotificationPreferenceMapper;
import com.auraboot.framework.im.model.ImNotificationPreference;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Manages per-user notification preferences for IM event notifications.
 *
 * Resolution order (most specific wins):
 * 1. model_code + operation_type match (e.g., "crm_lead" + "state_transition")
 * 2. model_code match (e.g., "crm_lead" + NULL)
 * 3. operation_type match (e.g., NULL + "state_transition")
 * 4. Global default (NULL + NULL) — defaults to enabled if no preference exists
 *
 * @since 6.2.0
 */
@Service
@RequiredArgsConstructor
public class ImNotificationPreferenceService {

    private final ImNotificationPreferenceMapper mapper;

    /**
     * Check if notifications are enabled for a given user, model, and operation type.
     * Returns true if no preference is configured (default: enabled).
     */
    public boolean isEnabled(Long userId, Long tenantId, String modelCode, String operationType) {
        ImNotificationPreference pref = mapper.findMostSpecific(tenantId, userId, modelCode, operationType);
        return pref == null || pref.getEnabled();
    }

    /**
     * List all preferences for a user.
     */
    public List<ImNotificationPreference> listByUser(Long userId, Long tenantId) {
        return mapper.findByUser(tenantId, userId);
    }

    /**
     * Set a notification preference (upsert).
     */
    public ImNotificationPreference setPreference(Long userId, Long tenantId,
                                                    String modelCode, String operationType,
                                                    boolean enabled) {
        // Find existing
        QueryWrapper<ImNotificationPreference> query = new QueryWrapper<>();
        query.eq("tenant_id", tenantId)
                .eq("user_id", userId);
        if (modelCode != null) {
            query.eq("model_code", modelCode);
        } else {
            query.isNull("model_code");
        }
        if (operationType != null) {
            query.eq("operation_type", operationType);
        } else {
            query.isNull("operation_type");
        }

        ImNotificationPreference existing = mapper.selectOne(query);
        if (existing != null) {
            existing.setEnabled(enabled);
            existing.setUpdatedAt(Instant.now());
            mapper.updateById(existing);
            return existing;
        }

        ImNotificationPreference pref = new ImNotificationPreference();
        pref.setUserId(userId);
        pref.setTenantId(tenantId);
        pref.setModelCode(modelCode);
        pref.setOperationType(operationType);
        pref.setEnabled(enabled);
        pref.setCreatedAt(Instant.now());
        pref.setUpdatedAt(Instant.now());
        mapper.insert(pref);
        return pref;
    }

    /**
     * Delete a specific preference, reverting to default behavior.
     */
    public void deletePreference(Long preferenceId, Long userId, Long tenantId) {
        mapper.delete(new QueryWrapper<ImNotificationPreference>()
                .eq("id", preferenceId)
                .eq("user_id", userId)
                .eq("tenant_id", tenantId));
    }
}
