/**
 * DTOs for unified plugin package system.
 *
 * <p>This package contains data transfer objects for the unified plugin package
 * architecture that integrates:
 * <ul>
 *   <li>Configuration Import - DSL configs (models, fields, pages, commands)</li>
 *   <li>PF4J Hot-Loading - Backend JAR plugins</li>
 *   <li>Module Federation - Frontend components</li>
 * </ul>
 *
 * <p>Key classes:
 * <ul>
 *   <li>{@link com.auraboot.framework.plugin.dto.packages.PackageManifest} - Unified manifest (plugin.json)</li>
 *   <li>{@link com.auraboot.framework.plugin.dto.packages.PackageParseResult} - Parse result with detected components</li>
 *   <li>{@link com.auraboot.framework.plugin.dto.packages.PackageInstallResult} - Installation result with component status</li>
 *   <li>{@link com.auraboot.framework.plugin.dto.packages.PackageUninstallResult} - Uninstallation result</li>
 *   <li>{@link com.auraboot.framework.plugin.dto.packages.PackageStatusDTO} - Plugin status for API</li>
 *   <li>{@link com.auraboot.framework.plugin.dto.packages.PackageHistoryDTO} - Installation history for API</li>
 * </ul>
 */
package com.auraboot.framework.plugin.dto.packages;
