package com.auraboot.framework.userattribute.service;

import com.auraboot.framework.userattribute.entity.AbUserAttribute;
import com.auraboot.framework.userattribute.mapper.AbUserAttributeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves user attributes consumed by the semantic-layer RLS engine.
 *
 * <p>{@link com.auraboot.framework.semantic.compiler.AccessPolicyCompiler}
 * calls {@link #getAttributes(Long, Long)} to build the {@code Map<String, String>}
 * passed inside {@code UserContext}, then expands placeholders such as
 * {@code {user.allowed_regions}} into parameterised SQL.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserAttributeService {

    private final AbUserAttributeMapper mapper;

    /**
     * Return all attributes for a user as a {@code Map<code, value>}.
     * Returns an empty map if the user has none — callers downstream
     * will throw {@code USER_ATTRIBUTE_MISSING} for any policy that
     * actually needs an unresolved key.
     */
    public Map<String, String> getAttributes(Long tenantId, Long userId) {
        if (tenantId == null || userId == null) {
            return Map.of();
        }
        List<AbUserAttribute> rows = mapper.listByUser(tenantId, userId);
        Map<String, String> out = new HashMap<>(rows.size() * 2);
        for (AbUserAttribute row : rows) {
            out.put(row.getAttributeCode(), row.getAttributeValue());
        }
        return out;
    }

    public Optional<String> get(Long tenantId, Long userId, String code) {
        return Optional.ofNullable(mapper.findOne(tenantId, userId, code))
                .map(AbUserAttribute::getAttributeValue);
    }
}
