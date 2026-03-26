package com.auraboot.framework.bpm.engine;

/**
 * Factory that resolves a {@link BpmEngine} by engine type identifier.
 * <p>
 * In most deployments only one engine is active, but the factory allows
 * runtime selection when multiple adapters are on the classpath.
 */
public interface BpmEngineFactory {

    /**
     * Return the {@link BpmEngine} for the given type.
     *
     * @param engineType one of "smartengine", "camunda", "flowable"
     * @return the corresponding engine adapter
     * @throws IllegalArgumentException if no adapter is registered for the type
     */
    BpmEngine create(String engineType);
}
