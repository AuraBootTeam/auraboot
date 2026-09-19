package com.auraboot.framework.meta.event;

import org.springframework.context.ApplicationEvent;

/**
 * Spring Application Event published synchronously inside the model publish transaction right
 * after the dynamic table was created successfully.
 *
 * <p>Consumed by {@code ModelPublishHookDispatcher} to run plugin-provided post-publish hooks
 * (installable guard triggers and similar per-publish DDL). A listener that throws fails the
 * publish, so a model can never end up published without a guard it declared.</p>
 */
public class ModelTablePublishedEvent extends ApplicationEvent {

    private final String modelCode;

    public ModelTablePublishedEvent(Object source, String modelCode) {
        super(source);
        this.modelCode = modelCode;
    }

    public String getModelCode() {
        return modelCode;
    }
}
