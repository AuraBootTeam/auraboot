package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.service.CommandService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.impl.CommandAutoSetExecutor;
import com.auraboot.framework.plugin.extension.NativeBusinessCodeAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Command-local read capability for published auto-code rules, never a nested command or a model permission grant. */
public final class NativeBusinessCodeAccessorImpl implements NativeBusinessCodeAccessor {
    private record Rule(String commandCode,String fieldCode) {}
    private final Long tenant;
    private final String actor,root;
    private final Set<Rule> rules;
    private final CommandService commands;
    private final MetaModelService models;
    private final CommandAutoSetExecutor generator;
    private final ObjectMapper json;
    private NativeBusinessCodeAccessorImpl(Long tenant,String actor,String root,Set<Rule> rules,
            CommandService commands,MetaModelService models,CommandAutoSetExecutor generator,ObjectMapper json) {
        if(tenant==null || tenant<=0 || actor==null || actor.isBlank() || root==null || root.isBlank()) throw new SecurityException("AUTO_CODE_CONTEXT_REQUIRED");
        this.tenant=tenant;this.actor=actor;this.root=root;this.rules=rules;
        this.commands=Objects.requireNonNull(commands);this.models=Objects.requireNonNull(models);
        this.generator=Objects.requireNonNull(generator);this.json=Objects.requireNonNull(json);
    }
    /** Install only from authorized server-loaded handler metadata. */
    public static void install(Map<String,Object> settings,Long tenant,String actor,String root,CommandService commands,
            MetaModelService models,CommandAutoSetExecutor generator,ObjectMapper json) {
        settings.remove(SETTINGS_KEY);
        Object raw=settings.get(SCOPE_HANDLER_PARAM);
        if(raw==null) return;
        if(!(raw instanceof List<?> entries) || entries.isEmpty() || entries.size()>32) throw new IllegalArgumentException("AUTO_CODE_SCOPE_REQUIRED");
        var rules=new LinkedHashSet<Rule>();
        for(Object item:entries) {
            if(!(item instanceof Map<?,?> entry) || !entry.keySet().equals(Set.of("commandCode","fieldCode"))
                    || !(entry.get("commandCode") instanceof String command) || !command.matches("[a-zA-Z][a-zA-Z0-9_]{0,99}:[a-zA-Z][a-zA-Z0-9_]{0,99}")
                    || !(entry.get("fieldCode") instanceof String field) || !field.matches("[a-zA-Z][a-zA-Z0-9_]{0,99}")
                    || !rules.add(new Rule(command,field))) throw new IllegalArgumentException("AUTO_CODE_SCOPE_INVALID");
        }
        settings.put(SETTINGS_KEY,new NativeBusinessCodeAccessorImpl(tenant,actor,root,Set.copyOf(rules),commands,models,generator,json));
    }
    public String generate(String commandCode,String fieldCode) {
        if(!tenant.equals(MetaContext.getCurrentTenantId()) || !actor.equals(MetaContext.getCurrentUserPid())
                || !root.equals(MetaContext.getAuthorizedCommandCode()) || !MetaContext.isRootAuthorizedCommand()) throw new SecurityException("AUTO_CODE_CONTEXT_MISMATCH");
        if(!rules.contains(new Rule(commandCode,fieldCode))) throw new SecurityException("AUTO_CODE_SCOPE_DENIED");
        var command=commands.findByCode(commandCode);
        if(command==null || !commandCode.equals(command.getCode()) || !tenant.equals(command.getTenantId())
                || !"published".equals(command.getStatus()) || !Boolean.TRUE.equals(command.getIsCurrent())
                || !"create".equals(command.getType()) || command.getModelCode()==null || command.getModelCode().isBlank())
            throw new IllegalStateException("AUTO_CODE_COMMAND_UNAVAILABLE");
        Map<String,Object> config;
        try {config=json.readerFor(new TypeReference<Map<String,Object>>(){}).with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(command.getExecutionConfig());}
        catch(JsonProcessingException invalid){throw new IllegalStateException("AUTO_CODE_RULE_INVALID");}
        if(config==null || !(config.get("autoSetFields") instanceof Map<?,?> fields)
                || !(fields.get(fieldCode) instanceof Map<?,?> rule) || !"auto_generate".equals(rule.get("strategy"))) throw new IllegalStateException("AUTO_CODE_RULE_INVALID");
        var field=models.getFieldDefinition(command.getModelCode(),fieldCode);
        if(field==null || !fieldCode.equals(field.getCode()) || !"string".equalsIgnoreCase(field.getDataType())) throw new IllegalStateException("AUTO_CODE_FIELD_UNAVAILABLE");
        @SuppressWarnings("unchecked") var typed=(Map<String,Object>)rule;
        String code=generator.generateRequiredAutoCode(command.getModelCode(),fieldCode,typed);
        if(code==null || code.isBlank()) throw new IllegalStateException("AUTO_CODE_GENERATION_UNAVAILABLE");
        return code;
    }
}
