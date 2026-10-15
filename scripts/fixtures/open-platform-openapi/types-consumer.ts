import type { Resource, CreateInput, UpdateInput, ModelEvent } from "./resource.js";
import type { QueryParameters, QueryItem } from "./query.js";
const create: CreateInput = { title: "Part", cost: "1.25", tags: ["one", null], profile: { label: "Blue", secret: null } };
const update: UpdateInput = { cost: null };
const resource: Resource = { pid: "part-x", profile: {}, cost: null };
const parameters: QueryParameters = { zones: ["A"] };
const item: QueryItem = { count: 2 };
const event: ModelEvent = { id: "evt_fixture", type: "open.resource.fixture.parts.updated", schemaVersion: 3, occurredAt: "2026-10-10T00:00:00Z", subject: { type: "fixture.parts", pid: "part-x" }, recordVersion: 2, correlationId: null, data: resource };
// @ts-expect-error required create title
const missing: CreateInput = {};
// @ts-expect-error read-only date cannot be written
const readonlyWrite: CreateInput = { title: "Part", day: "2026-10-10" };
// @ts-expect-error internal source alias cannot be written
const sourceWrite: CreateInput = { title: "Part", internal_name: "leak" };
// @ts-expect-error write-only fields cannot appear in resource
const inputLeak: Resource = { pid: "part-x", inputOnly: "leak" };
// @ts-expect-error nested object replacement requires label
const nestedPatch: UpdateInput = { profile: { secret: "x" } };
// @ts-expect-error typed query array cannot contain numbers
const wrongParameters: QueryParameters = { zones: [12] };
// @ts-expect-error event schema must match the publication
const wrongEvent: ModelEvent = { ...event, schemaVersion: 2 };
console.log(create, update, parameters, item, event);
