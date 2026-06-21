package com.auraboot.framework.permission.capability;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.plugin.dto.imports.CapabilityDefinitionDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CapabilityRegistryServiceImpl implements CapabilityRegistryService {

    private final CapabilityMapper capabilityMapper;

    @Override
    public void saveDefinition(CapabilityDefinitionDTO dto) {
        Long tenantId = MetaContext.getCurrentTenantId();
        CapabilityRecord record = toRecord(dto, tenantId);
        CapabilityRecord existing = capabilityMapper.findByTenantAndCode(tenantId, dto.getCode());
        if (existing != null) {
            record.setId(existing.getId());
            capabilityMapper.updateById(record);
        } else {
            capabilityMapper.insert(record);
        }
    }

    @Override
    public List<CapabilityDefinitionDTO> listDeclarations(Long tenantId) {
        return capabilityMapper.findByTenant(tenantId).stream().map(this::toDto).toList();
    }

    private CapabilityRecord toRecord(CapabilityDefinitionDTO dto, Long tenantId) {
        CapabilityRecord r = new CapabilityRecord();
        r.setTenantId(tenantId);
        r.setCode(dto.getCode());
        r.setGroupName(dto.getGroup());
        r.setName(dto.getNameZhCN());
        r.setNameEn(dto.getNameEn());
        r.setDescription(dto.getDescription());
        r.setIncludes(joinCsv(dto.getIncludes()));
        r.setTier(dto.getTier());
        r.setSensitive(Boolean.TRUE.equals(dto.getSensitive()));
        r.setUnmasksFields(joinCsv(dto.getUnmasksFields()));
        r.setOrderNo(dto.getOrder() == null ? 100 : dto.getOrder());
        return r;
    }

    private CapabilityDefinitionDTO toDto(CapabilityRecord r) {
        return CapabilityDefinitionDTO.builder()
                .code(r.getCode())
                .group(r.getGroupName())
                .nameZhCN(r.getName())
                .nameEn(r.getNameEn())
                .description(r.getDescription())
                .includes(splitCsv(r.getIncludes()))
                .tier(r.getTier())
                .sensitive(Boolean.TRUE.equals(r.getSensitive()))
                .unmasksFields(splitCsv(r.getUnmasksFields()))
                .order(r.getOrderNo())
                .build();
    }

    static String joinCsv(List<String> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        return String.join(",", items);
    }

    static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
