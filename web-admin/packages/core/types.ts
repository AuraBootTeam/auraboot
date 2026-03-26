/**
 * @auraboot/core type definitions
 *
 * Shared types for the route manifest system and edition builds.
 * During monorepo phase these are standalone definitions.
 * After repo split these become the canonical package exports.
 */

import type { RouteConfigEntry } from '@react-router/dev/routes';

/** A complete route manifest (array of route config entries). */
export type RouteManifest = RouteConfigEntry[];

/** A single route entry within a manifest. */
export type RouteEntry = RouteConfigEntry;

/** Edition identifiers for conditional route loading. */
export type Edition = 'community' | 'enterprise' | 'website';
