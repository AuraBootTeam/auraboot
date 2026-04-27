package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.meta.constant.Status;
import com.auraboot.framework.meta.dto.BindingRuleDTO;
import com.auraboot.framework.meta.dto.CommandDefinitionCreateRequest;
import com.auraboot.framework.meta.dto.CommandDefinitionDTO;
import com.auraboot.framework.meta.entity.BindingRule;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.mapper.BindingRuleMapper;
import com.auraboot.framework.meta.mapper.CommandDefinitionMapper;
import com.auraboot.framework.meta.service.CommandService;
import com.auraboot.framework.common.util.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * Command Service implementation
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommandServiceImpl implements CommandService {
    private final CommandDefinitionMapper commandDefinitionMapper;
    private final BindingRuleMapper bindingRuleMapper;
    private final CommandMetadataCacheService commandMetadataCache;

    @Override
    @Transactional
    public CommandDefinitionDTO create(CommandDefinitionCreateRequest request) {
        log.info("Creating command definition: {}", request.getCode());

        Long tenantId = MetaContext.getCurrentTenantId();

        // Check code uniqueness (tenant_id is automatically added by TenantLineInnerInterceptor)
        CommandDefinition existing = commandDefinitionMapper.findCurrentByCode(request.getCode());
        if (existing != null) {
            throw new BusinessException(ResponseCode.BadParam, "Command code already exists: " + request.getCode());
        }

        CommandDefinition entity = new CommandDefinition();
        entity.setPid(UniqueIdGenerator.generate());
        entity.setTenantId(tenantId);
        entity.setCode(request.getCode());
        entity.setDisplayName(request.getDisplayName());
        entity.setDescription(request.getDescription());
        entity.setModelCode(request.getModelCode());
        entity.setInputSchema(request.getInputSchema() != null ? request.getInputSchema() : "{}");
        entity.setTargetModels(request.getTargetModels() != null ? request.getTargetModels() : "[]");
        entity.setExecutionConfig(request.getExecutionConfig() != null ? request.getExecutionConfig() : "{}");
        entity.setCmdRiskLevel(request.getCmdRiskLevel() != null && !request.getCmdRiskLevel().isBlank()
                ? request.getCmdRiskLevel()
                : "L1");
        entity.setExtension(new com.auraboot.framework.meta.entity.payload.ExtensionBean());
        entity.setVersion(1);
        entity.setIsCurrent(true);
        entity.setRowVersion(1);
        entity.setStatus(Status.DRAFT.getCode());
        entity.setDeletedFlag(false);
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());

        // Set plugin_pid if provided
        if (request.getPluginPid() != null && !request.getPluginPid().isEmpty()) {
            entity.setPluginPid(request.getPluginPid());
        }

        commandDefinitionMapper.insertIdempotent(entity);
        commandMetadataCache.evictCommandDefinitions();

        return toDTO(entity);
    }

    @Override
    @Transactional
    public CommandDefinitionDTO update(String pid, CommandDefinitionCreateRequest request) {
        log.info("Updating command definition: {}", pid);

        CommandDefinition entity = commandDefinitionMapper.findByPid(pid);
        if (entity == null) {
            throw new BusinessException(ResponseCode.BadParam, "Command not found: " + pid);
        }

        if (!Status.DRAFT.getCode().equals(entity.getStatus())) {
            throw new BusinessException(ResponseCode.BadParam, "Only DRAFT commands can be updated");
        }

        entity.setDisplayName(request.getDisplayName());
        entity.setDescription(request.getDescription());
        entity.setModelCode(request.getModelCode());
        if (request.getInputSchema() != null) {
            entity.setInputSchema(request.getInputSchema());
        }
        if (request.getTargetModels() != null) {
            entity.setTargetModels(request.getTargetModels());
        }
        if (request.getExecutionConfig() != null) {
            entity.setExecutionConfig(request.getExecutionConfig());
        }
        if (request.getCmdRiskLevel() != null && !request.getCmdRiskLevel().isBlank()) {
            entity.setCmdRiskLevel(request.getCmdRiskLevel());
        }
        entity.setUpdatedAt(Instant.now());

        commandDefinitionMapper.updateById(entity);
        commandMetadataCache.evictCommandDefinitions();

        return toDTO(entity);
    }

    @Override
    public CommandDefinitionDTO findByPid(String pid) {
        CommandDefinition entity = commandDefinitionMapper.findByPid(pid);
        if (entity == null) {
            throw new BusinessException(ResponseCode.BadParam, "Command not found: " + pid);
        }
        CommandDefinitionDTO dto = toDTO(entity);
        dto.setBindingRules(getBindingRules(pid));
        return dto;
    }

    @Override
    public CommandDefinitionDTO findByCode(String code) {
        // tenant_id is automatically added by TenantLineInnerInterceptor
        CommandDefinition entity = commandDefinitionMapper.findCurrentByCode(code);
        if (entity == null) {
            throw new BusinessException(ResponseCode.BadParam, "Command not found: " + code);
        }
        CommandDefinitionDTO dto = toDTO(entity);
        dto.setBindingRules(getBindingRulesInternal(entity.getId()));
        return dto;
    }

    @Override
    public List<CommandDefinitionDTO> listByModelCode(String modelCode) {
        // tenant_id is automatically added by TenantLineInnerInterceptor
        List<CommandDefinition> entities = commandDefinitionMapper.findByModelCode(modelCode);
        return entities.stream().map(this::toDTO).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void delete(String pid) {
        log.info("Deleting command definition: {}", pid);
        CommandDefinition entity = commandDefinitionMapper.findByPid(pid);
        if (entity == null) {
            throw new BusinessException(ResponseCode.BadParam, "Command not found: " + pid);
        }
        commandDefinitionMapper.softDelete(pid);
        commandMetadataCache.evictAll();
    }

    // ==================== Binding Rules ====================

    @Override
    @Transactional
    public BindingRuleDTO addBindingRule(String commandPid, BindingRuleDTO ruleDTO) {
        log.info("Adding binding rule to command: {}", commandPid);

        CommandDefinition command = commandDefinitionMapper.findByPid(commandPid);
        if (command == null) {
            throw new BusinessException(ResponseCode.BadParam, "Command not found: " + commandPid);
        }

        BindingRule rule = new BindingRule();
        rule.setPid(UniqueIdGenerator.generate());
        rule.setTenantId(command.getTenantId());
        rule.setCommandId(command.getId());
        rule.setRuleType(ruleDTO.getRuleType());
        rule.setExpression(ruleDTO.getExpression());
        rule.setTargetModel(ruleDTO.getTargetModel());
        rule.setTargetField(ruleDTO.getTargetField());
        rule.setSourceField(ruleDTO.getSourceField());
        rule.setHandlerClass(ruleDTO.getHandlerClass());
        rule.setEventType(ruleDTO.getEventType());
        rule.setConfig(ruleDTO.getConfig() != null ? ruleDTO.getConfig() : "{}");
        rule.setSequence(ruleDTO.getSequence() != null ? ruleDTO.getSequence() : 0);
        rule.setEnabled(ruleDTO.getEnabled() != null ? ruleDTO.getEnabled() : true);
        rule.setExtension(new com.auraboot.framework.meta.entity.payload.ExtensionBean());
        rule.setStatus(StatusConstants.ACTIVE);
        rule.setDeletedFlag(false);
        rule.setCreatedAt(Instant.now());
        rule.setUpdatedAt(Instant.now());

        bindingRuleMapper.insertRule(rule);
        commandMetadataCache.evictBindingRules();

        return toRuleDTO(rule);
    }

    @Override
    @Transactional
    public void removeBindingRule(String rulePid) {
        log.info("Removing binding rule: {}", rulePid);
        bindingRuleMapper.softDelete(rulePid);
        commandMetadataCache.evictBindingRules();
    }

    @Override
    public List<BindingRuleDTO> getBindingRules(String commandPid) {
        CommandDefinition command = commandDefinitionMapper.findByPid(commandPid);
        if (command == null) {
            return List.of();
        }
        return getBindingRulesInternal(command.getId());
    }

    @Override
    @Transactional
    public void reorderBindingRules(String commandPid, List<String> rulePids) {
        log.info("Reordering binding rules for command: {}", commandPid);
        for (int i = 0; i < rulePids.size(); i++) {
            bindingRuleMapper.updateSequence(rulePids.get(i), i);
        }
        commandMetadataCache.evictBindingRules();
    }

    // ==================== Publish ====================

    @Override
    @Transactional
    public CommandDefinitionDTO publish(String pid) {
        log.info("Publishing command definition: {}", pid);

        CommandDefinition entity = commandDefinitionMapper.findByPid(pid);
        if (entity == null) {
            throw new BusinessException(ResponseCode.BadParam, "Command not found: " + pid);
        }

        if (!Status.DRAFT.getCode().equals(entity.getStatus())) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "Only DRAFT commands can be published");
        }

        // Validate: command must have binding rules or execution_config
        List<BindingRule> rules = bindingRuleMapper.findByCommandId(entity.getId());
        boolean hasExecutionConfig = entity.getExecutionConfig() != null
                && !entity.getExecutionConfig().isBlank()
                && !"{}".equals(entity.getExecutionConfig().trim());
        if (rules.isEmpty() && !hasExecutionConfig) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Command must have at least one binding rule or execution config");
        }

        commandDefinitionMapper.updateStatus(entity.getId(), Status.PUBLISHED.getCode());
        entity.setStatus(Status.PUBLISHED.getCode());
        commandMetadataCache.evictCommandDefinitions();

        return toDTO(entity);
    }

    // ==================== Private Helpers ====================

    private List<BindingRuleDTO> getBindingRulesInternal(Long commandId) {
        List<BindingRule> rules = bindingRuleMapper.findByCommandId(commandId);
        return rules.stream().map(this::toRuleDTO).collect(Collectors.toList());
    }

    private CommandDefinitionDTO toDTO(CommandDefinition entity) {
        CommandDefinitionDTO dto = new CommandDefinitionDTO();
        dto.setId(entity.getId());
        dto.setPid(entity.getPid());
        dto.setTenantId(entity.getTenantId());
        dto.setCode(entity.getCode());
        dto.setDisplayName(entity.getDisplayName());
        dto.setDescription(entity.getDescription());
        dto.setModelCode(entity.getModelCode());
        dto.setType(extractCommandType(entity.getExecutionConfig()));
        dto.setInputSchema(entity.getInputSchema());
        dto.setTargetModels(entity.getTargetModels());
        dto.setExecutionConfig(entity.getExecutionConfig());
        dto.setCmdRiskLevel(entity.getCmdRiskLevel());
        dto.setVersion(entity.getVersion());
        dto.setSemver(entity.getSemver());
        dto.setIsCurrent(entity.getIsCurrent());
        dto.setStatus(entity.getStatus());
        if (entity.getCreatedAt() != null) {
            dto.setCreatedAt(java.time.LocalDateTime.ofInstant(entity.getCreatedAt(), java.time.ZoneOffset.UTC));
        }
        if (entity.getUpdatedAt() != null) {
            dto.setUpdatedAt(java.time.LocalDateTime.ofInstant(entity.getUpdatedAt(), java.time.ZoneOffset.UTC));
        }
        return dto;
    }

    private String extractCommandType(String executionConfig) {
        if (executionConfig == null || executionConfig.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> config = JsonUtil.parse(
                executionConfig,
                new TypeReference<Map<String, Object>>() {}
            );
            Object type = config.get("type");
            return type != null ? String.valueOf(type) : null;
        } catch (Exception e) {
            log.debug("Failed to parse command executionConfig for type extraction", e);
            return null;
        }
    }

    private BindingRuleDTO toRuleDTO(BindingRule rule) {
        BindingRuleDTO dto = new BindingRuleDTO();
        dto.setId(rule.getId());
        dto.setPid(rule.getPid());
        dto.setCommandId(rule.getCommandId());
        dto.setRuleType(rule.getRuleType());
        dto.setExpression(rule.getExpression());
        dto.setTargetModel(rule.getTargetModel());
        dto.setTargetField(rule.getTargetField());
        dto.setSourceField(rule.getSourceField());
        dto.setHandlerClass(rule.getHandlerClass());
        dto.setEventType(rule.getEventType());
        dto.setConfig(rule.getConfig());
        dto.setSequence(rule.getSequence());
        dto.setEnabled(rule.getEnabled());
        if (rule.getCreatedAt() != null) {
            dto.setCreatedAt(java.time.LocalDateTime.ofInstant(rule.getCreatedAt(), java.time.ZoneOffset.UTC));
        }
        if (rule.getUpdatedAt() != null) {
            dto.setUpdatedAt(java.time.LocalDateTime.ofInstant(rule.getUpdatedAt(), java.time.ZoneOffset.UTC));
        }
        return dto;
    }
}
