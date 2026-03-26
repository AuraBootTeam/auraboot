package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.annotation.CommandPhase;
import com.auraboot.framework.meta.constant.CommandStage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Registry that scans all Spring beans annotated with {@link CommandPhase}
 * at startup and builds a stage-to-handlers mapping for discoverability.
 *
 * <p>This registry is read-only after initialization and provides introspection
 * APIs consumed by the REST endpoint {@code GET /api/meta/command-phases}.
 *
 * @author AuraBoot Team
 * @since 2.5.0
 */
@Slf4j
@Component
public class CommandPhaseRegistry {

    private final ApplicationContext applicationContext;

    /** stage number -> list of handler descriptors */
    private final Map<Integer, List<PhaseHandlerDescriptor>> stageHandlerMap = new ConcurrentHashMap<>();

    /** all discovered phase descriptors, sorted by stage */
    private List<PhaseDescriptor> allPhases;

    public CommandPhaseRegistry(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @PostConstruct
    void init() {
        Map<String, Object> beans = applicationContext.getBeansWithAnnotation(CommandPhase.class);
        log.info("Scanning {} beans annotated with @CommandPhase", beans.size());

        for (Map.Entry<String, Object> entry : beans.entrySet()) {
            String beanName = entry.getKey();
            Object bean = entry.getValue();
            CommandPhase annotation = bean.getClass().getAnnotation(CommandPhase.class);
            if (annotation == null) {
                // Could be on a proxy — check the target class
                annotation = org.springframework.core.annotation.AnnotationUtils.findAnnotation(
                        bean.getClass(), CommandPhase.class);
            }
            if (annotation == null) {
                log.warn("Bean {} has @CommandPhase annotation but could not be resolved", beanName);
                continue;
            }

            PhaseHandlerDescriptor descriptor = new PhaseHandlerDescriptor(
                    beanName,
                    bean.getClass().getName(),
                    annotation.name(),
                    annotation.stage(),
                    annotation.transactional(),
                    annotation.interruptible(),
                    annotation.description()
            );

            for (int stage : annotation.stage()) {
                stageHandlerMap.computeIfAbsent(stage, k -> new ArrayList<>()).add(descriptor);
            }
        }

        // Build the sorted allPhases list
        this.allPhases = buildAllPhases();
        log.info("CommandPhaseRegistry initialized: {} stages with handlers, {} total handler registrations",
                stageHandlerMap.size(), stageHandlerMap.values().stream().mapToInt(List::size).sum());
    }

    /**
     * Returns all phases (stages 1-24) with their registered handlers.
     * Stages without explicit handler beans are still listed with empty handler lists.
     */
    public List<PhaseDescriptor> getAllPhases() {
        return Collections.unmodifiableList(allPhases);
    }

    /**
     * Returns handlers registered for the given stage number.
     */
    public List<PhaseHandlerDescriptor> getHandlersByStage(int stage) {
        return Collections.unmodifiableList(stageHandlerMap.getOrDefault(stage, Collections.emptyList()));
    }

    /**
     * Returns the total number of stages that have at least one handler registered.
     */
    public int getAnnotatedStageCount() {
        return stageHandlerMap.size();
    }

    private List<PhaseDescriptor> buildAllPhases() {
        List<PhaseDescriptor> phases = new ArrayList<>();
        // Include all stages 1 through max known stage
        int maxStage = Math.max(CommandStage.GOVERNANCE_SNAPSHOT,
                stageHandlerMap.keySet().stream().mapToInt(Integer::intValue).max().orElse(0));

        for (int stage = 1; stage <= maxStage; stage++) {
            String name = CommandStage.nameOf(stage);
            boolean transactional = stage <= CommandStage.TOTAL_TRANSACTIONAL_STAGES;
            List<PhaseHandlerDescriptor> handlers = stageHandlerMap.getOrDefault(stage, Collections.emptyList());
            phases.add(new PhaseDescriptor(stage, name, transactional, handlers));
        }
        return phases;
    }

    // ==================== DTOs ====================

    /**
     * Describes a single pipeline phase (stage).
     */
    public record PhaseDescriptor(
            int stage,
            String name,
            boolean transactional,
            List<PhaseHandlerDescriptor> handlers
    ) {}

    /**
     * Describes a handler bean registered for one or more phases.
     */
    public record PhaseHandlerDescriptor(
            String beanName,
            String className,
            String handlerName,
            int[] stages,
            boolean transactional,
            boolean interruptible,
            String description
    ) {}
}
