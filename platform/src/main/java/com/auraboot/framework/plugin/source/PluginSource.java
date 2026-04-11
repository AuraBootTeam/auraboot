package com.auraboot.framework.plugin.source;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Abstraction for reading plugin resources from different sources.
 *
 * <p>Implementations:</p>
 * <ul>
 *   <li>{@link FileSystemPluginSource} — local directory (current default)</li>
 *   <li>Future: URL-based source for marketplace downloads</li>
 *   <li>Future: S3/OSS source for cloud deployments</li>
 * </ul>
 *
 * @since 7.2.0
 */
public interface PluginSource {

    /**
     * Get the source identifier for logging/tracking.
     */
    String getSourceId();

    /**
     * Check if a resource exists at the given path (relative to plugin root).
     */
    boolean exists(String relativePath);

    /**
     * Read a resource as an InputStream.
     *
     * @param relativePath path relative to plugin root (e.g., "plugin.json", "models/crm_lead.json")
     * @return input stream for the resource
     * @throws IOException if the resource cannot be read
     */
    InputStream readResource(String relativePath) throws IOException;

    /**
     * Read a resource as a UTF-8 string.
     *
     * @param relativePath path relative to plugin root
     * @return string content
     * @throws IOException if the resource cannot be read
     */
    String readString(String relativePath) throws IOException;

    /**
     * List all files in a subdirectory (non-recursive), filtered by extension.
     *
     * @param relativeDir directory relative to plugin root (e.g., "models")
     * @param extension   file extension filter (e.g., ".json"), null for all files
     * @return list of relative paths to files
     * @throws IOException if the directory cannot be listed
     */
    List<String> listFiles(String relativeDir, String extension) throws IOException;

    /**
     * Check if the source represents a valid plugin (has plugin.json).
     */
    default boolean isValidPlugin() {
        return exists("plugin.json");
    }
}
