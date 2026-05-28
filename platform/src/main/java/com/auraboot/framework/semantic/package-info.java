/**
 * Semantic layer v0.1 — file-based metric/dimension/access-policy definitions.
 *
 * <p>Backed by 6 tables created in migration
 * {@code database/migrations/2026-05-28-semantic-layer-v01.sql}.
 *
 * <p>Plugins declare a {@code *.semantic.yml} file in
 * {@code resourceDirs.semantic} and the platform imports the model into:
 * <ul>
 *   <li>{@link com.auraboot.framework.semantic.entity.AbSemanticModel} — top-level</li>
 *   <li>{@link com.auraboot.framework.semantic.entity.AbSemanticDimension} — N dimensions</li>
 *   <li>{@link com.auraboot.framework.semantic.entity.AbSemanticMetric} — N metrics, 5 types</li>
 *   <li>{@link com.auraboot.framework.semantic.entity.AbSemanticLineageEdge} — auto-built graph</li>
 *   <li>{@link com.auraboot.framework.semantic.entity.AbSemanticExposure} — Dashboard / app deps</li>
 *   <li>{@link com.auraboot.framework.semantic.entity.AbSemanticQueryLog} — audit log</li>
 * </ul>
 *
 * <p>See PRD {@code ida/docs/16-prd-semantic-yml-dsl.md} for the full design.
 *
 * @since 1.0.0 (IDA P0-1)
 */
package com.auraboot.framework.semantic;
