package com.auraboot.framework.plugin.extension;

import org.pf4j.ExtensionPoint;

/**
 * Extension point for long-running background components contributed by a plugin
 * that need to live inside the host's Spring {@code ApplicationContext}.
 *
 * <p>Unlike {@link CommandHandlerExtension} which is invoked per-request with a
 * {@code CommandContext}, components declared via this extension are registered
 * as singletons in the host bean factory when the plugin is enabled. They
 * receive full Spring lifecycle treatment:
 *
 * <ul>
 *   <li>Field/constructor {@code @Autowired} from host beans is resolved.</li>
 *   <li>All {@code BeanPostProcessor}s run — so {@code @KafkaListener},
 *       {@code @Scheduled}, {@code @PostConstruct}, {@code @EventListener}
 *       fire as if the class were a host {@code @Service}.</li>
 *   <li>On plugin disable, {@code @PreDestroy} runs and the singleton is removed.</li>
 * </ul>
 *
 * <p>Use this for:
 * <ul>
 *   <li>Kafka / message consumers</li>
 *   <li>Scheduled tasks (lease scanners, reconcilers, cache warmers)</li>
 *   <li>In-process caches / connection pools that the plugin needs to own</li>
 *   <li>Any component that needs DI from host services outside a request context</li>
 * </ul>
 *
 * <p><b>Implementation contract:</b>
 * <pre>
 *   &#64;Extension
 *   public class MySeedConsumer implements BackgroundComponentExtension {
 *       &#64;Autowired private SomeHostService hostService;
 *       &#64;KafkaListener(topics = "my.topic") public void onMessage(...) { ... }
 *       &#64;PostConstruct public void start() { ... }
 *       &#64;PreDestroy public void stop() { ... }
 *   }
 * </pre>
 *
 * <p><b>Classloader note:</b> The host's parent-first classloader gives the
 * plugin visibility to all host classes, so Spring annotations from
 * {@code spring-context}, {@code spring-kafka}, {@code spring-scheduling}, and
 * Jakarta lifecycle annotations resolve to the same {@code Class} instance the
 * host's {@code BeanPostProcessor}s look for.
 *
 * <p><b>Tearing down listeners:</b> The default {@code @KafkaListener} container
 * is owned by Spring's {@code KafkaListenerEndpointRegistry} which is not
 * unregistered automatically when the bean's singleton is removed. If the
 * component owns Kafka or scheduled work that must stop on disable, implement
 * {@code @PreDestroy} and stop the relevant containers explicitly.
 *
 * @since 2.5.0
 */
public interface BackgroundComponentExtension extends ExtensionPoint {

    /**
     * Optional explicit bean name. Default: simple class name with the first
     * letter lower-cased (Spring's standard convention).
     *
     * @return bean name override, or {@code null} to use the default
     */
    default String beanName() {
        return null;
    }
}
