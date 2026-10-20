# Metadata field authoring

Creating a field from the model's field-selection dialog requires two different values:

- The **business display name** is submitted as `uiSchema.label`, trimmed and nonblank. It is intended for generated business forms, lists and details.
- The **code** is the stable technical identifier. Its existing format validation remains in place.

Before a new field is submitted, the dialog calls `/api/meta/fields/key/{code}/unique`. An existing code keeps the dialog open and directs the user to select the existing field or choose another code. A failed uniqueness request does not fall back to creating the field. The new-field action does not implicitly create another version of an existing field. The backend's explicit version-update behavior is retained.

Creation and version updates retain the existing `ui_schema` column. A version update without a UI-schema payload preserves the previous configuration. Field DTOs expose the stored UI schema and use its nonblank label as the effective display name, retaining legacy extension/code fallback for older fields.

The generic form renderer reads the same label for parent fields and nested-table fields; existing localized-extension fallback is retained. List/detail renderers already consume field DTO display names. No business-specific React page is introduced.

Canceling field creation causes no uniqueness request, create request or binding write. Duplicate-code, required-name, illegal-code and create/bind payload behavior have unit tests. The customer designer journey additionally covers the real menu → model → create/bind → metadata readback → publish → CRUD generation path. A collected journey is not execution evidence; use the shared Enterprise acceptance runtime before claiming end-to-end usability.
