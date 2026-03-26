package com.auraboot.framework.meta.service;

import com.auraboot.framework.meta.dto.BindingRuleDTO;
import com.auraboot.framework.meta.dto.CommandDefinitionCreateRequest;
import com.auraboot.framework.meta.dto.CommandDefinitionDTO;

import java.util.List;

/**
 * Command Service interface for CRUD operations on CommandDefinition and BindingRule.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
public interface CommandService {

    // ==================== CRUD ====================

    CommandDefinitionDTO create(CommandDefinitionCreateRequest request);

    CommandDefinitionDTO update(String pid, CommandDefinitionCreateRequest request);

    CommandDefinitionDTO findByPid(String pid);

    CommandDefinitionDTO findByCode(String code);

    List<CommandDefinitionDTO> listByModelCode(String modelCode);

    void delete(String pid);

    // ==================== Binding Rules ====================

    BindingRuleDTO addBindingRule(String commandPid, BindingRuleDTO rule);

    void removeBindingRule(String rulePid);

    List<BindingRuleDTO> getBindingRules(String commandPid);

    void reorderBindingRules(String commandPid, List<String> rulePids);

    // ==================== Publish ====================

    CommandDefinitionDTO publish(String pid);
}
