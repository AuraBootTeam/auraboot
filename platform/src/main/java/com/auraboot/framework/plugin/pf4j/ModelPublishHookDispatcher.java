package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.meta.event.ModelTablePublishedEvent;
import com.auraboot.framework.plugin.extension.ModelPublishHookExtension;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;

/**
 * Dispatches model-table publish events to plugin-provided {@link ModelPublishHookExtension}s.
 *
 * <p>Runs synchronously in the publisher's thread and transaction: a hook that throws fails the
 * model publish, so a table can never end up published while a declared guard failed to install.
 * Hooks are re-run on every publish and must be idempotent. Hooks receive the transaction-bound
 * {@link Connection} so their DDL commits (or rolls back) atomically with the publish.</p>
 */
@Slf4j
@Service
public class ModelPublishHookDispatcher {

    private final AuraPluginManager pluginManager;
    private final DataSource dataSource;

    public ModelPublishHookDispatcher(AuraPluginManager pluginManager, DataSource dataSource) {
        this.pluginManager = pluginManager;
        this.dataSource = dataSource;
    }

    @EventListener(ModelTablePublishedEvent.class)
    public void onModelTablePublished(ModelTablePublishedEvent event) {
        List<ModelPublishHookExtension> hooks = pluginManager.getExtensionsOfType(ModelPublishHookExtension.class);
        if (hooks.isEmpty()) {
            return;
        }
        log.info("Running {} model publish hook(s) for model {}", hooks.size(), event.getModelCode());
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            for (ModelPublishHookExtension hook : hooks) {
                hook.afterModelTablePublished(event.getModelCode(), connection);
            }
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }
}
