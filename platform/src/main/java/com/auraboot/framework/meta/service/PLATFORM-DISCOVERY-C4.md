# C-4 Platform Discovery Notes (working file, deleted at T10)

## 1. `MetaFieldService.create` autobind path

- `MetaFieldServiceImpl.create` at line 64 delegates to `createDirectly` (line 77).
- `createDirectly` builds `Field` entity, `autoPublish=true` sets `status="published"` (line 95); else `"draft"`.
- Auto-bind triggers at line 130: `if (StringUtils.hasText(request.getModelPid())) bindFieldToModelAfterCreation(...)`.
- Bind helper (line 832) looks up field via `getNextVersion`-1 + `findByCodeAndVersion`, then calls `modelFieldBindingService.bindFieldToModel(modelPid, fieldPid, null, false, false, true)`. Catches `Exception` and only logs (line 866) — bind failure does NOT abort field creation.
- `request.getExtension()` round-trips via `extensionConverter.toBean` (line 109); `displayName` lives inside extension map.

**Conclusion**: For `field:add`, calling `create(MetaFieldCreateRequest{modelPid, autoPublish=true, extension={displayName}})` will create field + bind + publish in one shot, but bind-failure is silent — caller must verify binding exists post-call.

## 2. `SchemaManagementService` dataType → PG mapping

- Mapping is delegated to `DdlDialectProvider.getDialect().mapDataType(field)` (`SchemaManagementServiceImpl` line 237 inside `generateColumnDefinition`).
- Canonical impl: `PostgresDdlDialect.mapDataType` (line 17), switch on `dataType.toLowerCase()`:
  - `string` → `varchar(maxLength|255)`; `text` → `text`; `integer` → `integer`; `long` → `bigint`;
  - `decimal`/`money` → `DECIMAL(precision,scale)` or `DECIMAL(19,precision)` or `DECIMAL(10,2)`;
  - `boolean` → `boolean`; `date` → `date`; `datetime`/`timestamp` → `timestamptz`; `time` → `time`;
  - `json`/`jsonb` → `jsonb`; `array` → `TEXT[]`; default → `varchar(255)` + WARN log.
- DDL applied via `ALTER TABLE <table> ADD COLUMN IF NOT EXISTS <columnDef>` (`SchemaManagementServiceImpl` line 454/1105).

**Conclusion**: `field:add` skill must NOT predict PG type. Read it from `information_schema.columns` post-`ALTER` to populate `AddFieldResult.pgColumnType`.

## 3. `FieldDefinition.columnName` derivation

- `FieldDefinition.columnName` is a plain field (`platform/src/main/java/com/auraboot/framework/meta/dto/FieldDefinition.java` line 52, `@Builder` lombok).
- `MetaFieldServiceImpl.createDirectly` does NOT set `columnName` on the persisted `Field` entity — it persists only `code` (line 96). The `FieldDefinition` DTO used during DDL generation is constructed elsewhere (when building schema DDL from model+field bindings), and there `columnName` is typically taken from `field.getCode()`.
- No explicit "storageCode → columnName" transformation found; `field.getCode()` IS the column name (passed directly into `validateIdentifier` + appended to DDL at `SchemaManagementServiceImpl` line 233).

**Conclusion**: `storageCode == columnName == field.code`. `field:add` should treat the user-supplied `code` as the literal PG column name (after sanitisation by `SqlSafetyUtils.validateIdentifier`).

## 4. `MetaModelService.unbindFieldFromModel` contract

- Located at `MetaModelServiceImpl` line 1589: `boolean unbindFieldFromModel(Long modelId, Long fieldId)`.
- Signature uses internal numeric IDs, not pids — caller must resolve.
- Refuses if binding `isSystemBinding=true` (line 1598) — throws `MetaServiceException`.
- DDL `ALTER TABLE DROP COLUMN` only triggers when `model.isPublished()` (line 1605); calls `schemaManagementService.removeFieldFromModel(modelCode, fieldCode)`.
- Wraps in `try { ... } catch (Exception e) { throw new MetaServiceException("解绑字段失败: ...", e); }` (line 1620) — system-binding rejection bubbles up wrapped.
- Returns `false` (not throws) when binding row didn't exist (line 1616).

**Conclusion**: `field:remove` must (a) resolve modelId+fieldId via codes, (b) handle `false` return as "already unbound", (c) catch wrapped `MetaServiceException` for system-binding refusal.

## 5. `MetaModelService.findByCode` null handling

- Interface declares `MetaModelDTO findByCode(String code)` (`MetaModelService.java` line 410).
- Impl at `MetaModelServiceImpl` line 646: throws `ValidationException(CommonValidationFailed, "模型不存在: " + code)` when `metaModelMapper.findCurrentByCode(code) == null`. NEVER returns null.
- No `findByCodeOrNull` / `findByCodeOrEmpty` variant exists in interface or impl.

**Conclusion**: Plan's `try { findByCode } catch (ValidationException) { ... }` IS the working pattern. Either use try/catch on `ValidationException`, or call `metaModelMapper.findCurrentByCode` directly (skipping DTO conversion) for null-tolerant lookup in `field:add` pre-flight checks.
