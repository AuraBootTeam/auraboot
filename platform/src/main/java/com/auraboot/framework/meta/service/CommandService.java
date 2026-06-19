package com.auraboot.framework.meta.service;

import com.auraboot.framework.meta.dto.BindingRuleDTO;
import com.auraboot.framework.meta.dto.CommandDefinitionCreateRequest;
import com.auraboot.framework.meta.dto.CommandDefinitionDTO;

import java.util.List;
import java.util.Map;

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

    /** List all current commands across every model (no model scope). */
    List<CommandDefinitionDTO> listAll();

    /**
     * Resolve the convention CRUD command codes for a model, keyed by operation
     * type ({@code create}/{@code update}/{@code delete}). Used by standard DSL
     * pages to route create/edit/delete through the model's business command
     * without configuring it per page or carrying it in the URL. The first
     * command matching each type wins; types with no command are omitted.
     * Returns an empty map for blank model codes or pure-CRUD models.
     */
    Map<String, String> resolveCrudCommands(String modelCode);

    void delete(String pid);

    // ==================== Binding Rules ====================

    BindingRuleDTO addBindingRule(String commandPid, BindingRuleDTO rule);

    void removeBindingRule(String rulePid);

    List<BindingRuleDTO> getBindingRules(String commandPid);

    void reorderBindingRules(String commandPid, List<String> rulePids);

    // ==================== Publish ====================

    CommandDefinitionDTO publish(String pid);
}
