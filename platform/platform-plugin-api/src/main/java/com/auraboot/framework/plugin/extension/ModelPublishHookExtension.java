package com.auraboot.framework.plugin.extension;

import org.pf4j.ExtensionPoint;

import java.sql.Connection;

/**
 * Extension point invoked synchronously inside the model publish transaction right after a
 * dynamic model table has been created.
 *
 * <p>Contract for implementations:</p>
 * <ul>
 *   <li><strong>Idempotent</strong> — every model publish (fresh install, re-import, manual
 *       republish) re-runs the hook; it must converge to the same end state.</li>
 *   <li><strong>Scoped</strong> — act only on model codes the owning plugin defines; return
 *       immediately for anything else.</li>
 *   <li><strong>Fail-closed</strong> — throw when the post-publish DDL cannot be applied. The
 *       publish then fails and the model stays unpublished; silently skipping would leave a
 *       published table without its declared guard (e.g. an immutability trigger).</li>
 *   <li><strong>Fast</strong> — runs on every publish, in the publish transaction.</li>
 * </ul>
 *
 * <p>Keep this copy and the platform main copy source-identical (see
 * {@code platform/src/main/java/com/auraboot/framework/plugin/extension/}).</p>
 */
public interface ModelPublishHookExtension extends ExtensionPoint {

    /**
     * @param modelCode  the model whose table was just published
     * @param connection the publish transaction's connection; the hook's statements join it
     */
    void afterModelTablePublished(String modelCode, Connection connection);
}
