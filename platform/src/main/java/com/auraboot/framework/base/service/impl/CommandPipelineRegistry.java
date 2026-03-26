package com.auraboot.framework.base.service.impl;

import com.auraboot.framework.base.annotation.CommandPhase;
import com.auraboot.framework.base.constant.CommandStage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry that discovers all beans annotated with {@link CommandPhase}
 * at startup and provides a queryable pipeline metadata model.
 *
 * <p>At startup, scans the ApplicationContext for @CommandPhase-annotated beans
 * and indexes them by stage number. Provides APIs for:
 * <ul>
 *   <li>Listing all pipeline phase definitions</li>
 *   <li>Listing handlers/executors registered at each phase</li>
 *   <li>Exporting the full pipeline structure as a serializable Map</li>
 * </ul>
 */
@Slf4j
@Component
public class CommandPipelineRegistry {

    private final ApplicationContext applicationContext;

    /** Stage number -> list of handler metadata entries. */
    private final ConcurrentHashMap<Integer, List<HandlerEntry>> stageHandlers = new ConcurrentHashMap<>();

    /** Stage number -> phase definition. */
    private final ConcurrentHashMap<Integer, PhaseDefinition> phaseDefinitions = new ConcurrentHashMap<>();

    public CommandPipelineRegistry(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /**
     * Metadata for a single pipeline phase definition.
     */
    public record PhaseDefinition(
            int stage,
            String name,
            String description,
            boolean interruptible,
            CommandPhase.TransactionMode transaction
    ) {}

    /**
     * Metadata for a handler/executor bean registered at a pipeline phase.
     */
    public record HandlerEntry(
            String beanName,
            String className,
            int stage,
            String phaseName,
            boolean interruptible,
            CommandPhase.TransactionMode transaction,
            String description
    ) {}

    @PostConstruct
    void init() {
        initCanonicalPhases();
        scanAnnotatedBeans();

        int totalHandlers = stageHandlers.values().stream().mapToInt(List::size).sum();
        log.info("CommandPipelineRegistry initialized: {} phases, {} handlers",
                phaseDefinitions.size(), totalHandlers);
    }

    /**
     * Initialize the 20+4 canonical pipeline phases with default metadata.
     */
    private void initCanonicalPhases() {
        for (int stage = 1; stage <= CommandStage.GOVERNANCE_SNAPSHOT; stage++) {
            String name = CommandStage.nameOf(stage);
            String description = CommandStage.descriptionOf(stage);
            boolean interruptible = isDefaultInterruptible(stage);
            CommandPhase.TransactionMode txMode = stage <= CommandStage.TOTAL_TRANSACTIONAL_STAGES
                    ? CommandPhase.TransactionMode.INHERITED
                    : CommandPhase.TransactionMode.NOT_SUPPORTED;
            phaseDefinitions.put(stage, new PhaseDefinition(stage, name, description, interruptible, txMode));
        }
    }

    /**
     * Scan all Spring beans annotated with @CommandPhase.
     */
    private void scanAnnotatedBeans() {
        Map<String, Object> beans = applicationContext.getBeansWithAnnotation(CommandPhase.class);
        log.info("Scanning {} beans annotated with @CommandPhase", beans.size());

        for (Map.Entry<String, Object> entry : beans.entrySet()) {
            String beanName = entry.getKey();
            Object bean = entry.getValue();
            CommandPhase annotation = AnnotationUtils.findAnnotation(bean.getClass(), CommandPhase.class);

            if (annotation == null) {
                log.warn("Bean {} has @CommandPhase annotation but could not be resolved", beanName);
                continue;
            }

            int stage = annotation.stage();
            String phaseName = annotation.name().isEmpty()
                    ? CommandStage.nameOf(stage)
                    : annotation.name();

            HandlerEntry handlerEntry = new HandlerEntry(
                    beanName,
                    bean.getClass().getName(),
                    stage,
                    phaseName,
                    annotation.interruptible(),
                    annotation.transaction(),
                    annotation.description()
            );

            stageHandlers.computeIfAbsent(stage, k -> Collections.synchronizedList(new ArrayList<>()))
                    .add(handlerEntry);

            // Enrich phase definition with annotation description if available
            if (!annotation.description().isEmpty() && phaseDefinitions.containsKey(stage)) {
                PhaseDefinition existing = phaseDefinitions.get(stage);
                if (existing.description().isEmpty()) {
                    phaseDefinitions.put(stage, new PhaseDefinition(
                            stage, phaseName, annotation.description(),
                            annotation.interruptible(), annotation.transaction()
                    ));
                }
            }

            log.debug("Registered pipeline handler: {} at stage {} ({})", beanName, stage, phaseName);
        }
    }

    // ==================== Query APIs ====================

    /**
     * Get all phase definitions, sorted by stage number.
     */
    public List<PhaseDefinition> getAllPhases() {
        return phaseDefinitions.values().stream()
                .sorted(Comparator.comparingInt(PhaseDefinition::stage))
                .toList();
    }

    /**
     * Get phase definition by stage number.
     */
    public Optional<PhaseDefinition> getPhase(int stage) {
        return Optional.ofNullable(phaseDefinitions.get(stage));
    }

    /**
     * Get all handlers registered at a specific stage.
     */
    public List<HandlerEntry> getHandlersAtStage(int stage) {
        return Collections.unmodifiableList(
                stageHandlers.getOrDefault(stage, Collections.emptyList()));
    }

    /**
     * Get all registered handlers across all stages, sorted by stage number.
     */
    public List<HandlerEntry> getAllHandlers() {
        return stageHandlers.values().stream()
                .flatMap(List::stream)
                .sorted(Comparator.comparingInt(HandlerEntry::stage))
                .toList();
    }

    /**
     * Returns the total number of phases.
     */
    public int getPhaseCount() {
        return phaseDefinitions.size();
    }

    /**
     * Returns the count of stages that have at least one handler.
     */
    public int getAnnotatedStageCount() {
        return stageHandlers.size();
    }

    /**
     * Export the full pipeline structure as a serializable list of maps.
     * Each entry includes the phase definition and its registered handlers.
     */
    public List<Map<String, Object>> exportPipeline() {
        return getAllPhases().stream()
                .map(phase -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("stage", phase.stage());
                    entry.put("name", phase.name());
                    entry.put("description", phase.description());
                    entry.put("interruptible", phase.interruptible());
                    entry.put("transaction", phase.transaction().name());
                    entry.put("transactional", phase.stage() <= CommandStage.TOTAL_TRANSACTIONAL_STAGES);

                    List<Map<String, Object>> handlers = getHandlersAtStage(phase.stage()).stream()
                            .map(h -> {
                                Map<String, Object> hMap = new LinkedHashMap<>();
                                hMap.put("beanName", h.beanName());
                                hMap.put("className", h.className());
                                hMap.put("interruptible", h.interruptible());
                                hMap.put("transaction", h.transaction().name());
                                hMap.put("description", h.description());
                                return hMap;
                            })
                            .toList();
                    entry.put("handlers", handlers);
                    entry.put("handlerCount", handlers.size());

                    return entry;
                })
                .toList();
    }

    /**
     * Default interruptibility per stage.
     * Validation-related phases are interruptible, data-write phases are not.
     */
    private boolean isDefaultInterruptible(int stage) {
        return switch (stage) {
            case CommandStage.SCHEMA_VALIDATE,
                 CommandStage.ENTITLEMENT_CHECK,
                 CommandStage.SOD_CHECK,
                 CommandStage.STATE_CHECK,
                 CommandStage.ASSERT,
                 CommandStage.PRE_INVARIANT,
                 CommandStage.CROSS_FIELD_VALIDATION,
                 CommandStage.HANDLER,
                 CommandStage.CONSISTENCY_CHECK,
                 CommandStage.POST_INVARIANT -> true;
            default -> false;
        };
    }
}
