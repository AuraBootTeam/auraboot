---
inclusion: always
---

# Project Structure

## Root Package
`com.auraboot.framework` - All code lives under this namespace

## Module Organization

The project follows a feature-based modular structure. Each module is self-contained with its own layers:

### Core Modules

#### `application/`
Bootstrap and cross-cutting concerns:
- `MetaApplication.java` - Main Spring Boot application class
- `annotation/` - Custom annotations (`@CurrentUserId`, `@RequirePermission`)
- `bootstrap/` - System initialization (`SystemDataInitializer`)
- `database/` - Database configuration (MyBatis, Snowflake ID, type handlers)
- `permission/` - Permission interceptor
- `security/` - Security config, password encoder, whitelist
- `tenant/` - Tenant context management (`MetaContext`)
- `web/` - Web layer config (filters, interceptors, handlers, resolvers)

#### `auth/`
Authentication and authorization:
- `controller/` - Auth endpoints (login, register)
- `dto/` - Auth request/response objects
- `service/` - Auth business logic, user details service
- `util/` - JWT utilities, current user utilities

#### `base/`
Base classes and interfaces:
- `dao/BaseMapper.java` - Base mapper interface (note: separate from MyBatis-Plus BaseMapper)
- `feature/` - Feature flag system
- `service/` - Base service interfaces and orchestration registry

#### `common/`
Shared utilities:
- `constant/` - Response codes and constants
- `dto/` - `ApiResponse`, `ApiResponse`
- `util/` - Utility classes (JSON, YAML, String, Date, Lambda, Naming, ThreadLocal, etc.)

#### `exception/`
Exception hierarchy:
- `BusinessException`, `ValidationException`, `PermissionDeniedException`
- `ResponseCode` enum

### Feature Modules

#### `meta/`
**Core low-code engine** - Largest and most important module:
- `bean/` - Configuration beans (I18n, QuerySchema, UiSchema, RuleSchema, PageLayout, etc.)
- `constant/` - Enums (DataType, FieldType, Status)
- `controller/` - REST endpoints (DictEntity, DictField, Dynamic, PageSchema, PageRender, PageRuntime)
- `converter/` - MapStruct converters
- `dto/` - Extensive DTOs for all operations (Entity, Field, Page, Dynamic CRUD, Batch operations, etc.)
- `entity/` - JPA entities for metadata storage
- `enums/` - Additional enumerations
- `mapper/` - MyBatis-Plus mappers
- `parser/` - Schema parsers
- `resolver/` - Schema resolvers
- `service/` - Business logic for metadata operations
- `strategy/` - Strategy pattern implementations
- `view/` - View models

#### `designer/`
Page designer and component management:
- `controller/` - Component registry, page designer, publish endpoints
- `converter/` - DTO converters
- `dto/` - Designer-specific DTOs
- `entity/` - `ComponentRegistry`, `PublishRecord`, `RoutePolicy`
- `mapper/` - MyBatis-Plus mappers
- `service/` - Designer business logic

#### `menu/`
Menu management:
- `constant/` - Menu enums (LinkType, MenuStatus, MenuType)
- `controller/`, `entity/`, `mapper/`, `service/` - Standard CRUD layers

#### `rbac/`
Role-based access control:
- Standard CRUD structure for roles, permissions, and assignments

#### `user/`
User management:
- `controller/`, `converter/`, `dao/`, `dto/`, `mapper/`, `service/` - User CRUD
- `exception/` - User-specific exceptions

#### `tenant/`
Multi-tenancy:
- Tenant management with standard CRUD structure

#### `file/`
File management:
- `constant/` - File enums (FileStatus, RelationType, StorageType, UploadStatus)
- `controller/` - File upload endpoints
- `entity/` - `FileEntity`, `FileRelationEntity`
- `service/` - File storage and relationship management

#### `i18n/`
Internationalization:
- `controller/` - I18n endpoints
- `service/` - Translation service
- `util/` - Locale resolver, text processor

#### `event/`
Event system:
- `config/` - Event configuration
- `core/` - Event interfaces and abstractions
- `listener/` - Event listeners
- `publisher/` - Local and distributed event publishers
- `user/` - User-related events

#### `datasource/`
Data source management:
- Dynamic data source configuration and access

#### `search/`
Search functionality (currently empty - planned feature)

## Layer Pattern

Each feature module typically follows this structure:
```
module/
├── controller/     # REST endpoints
├── service/        # Business logic
│   └── impl/      # Service implementations
├── mapper/         # MyBatis-Plus data access
├── entity/         # Database entities
├── dto/            # Data transfer objects
│   ├── *Request   # Input DTOs
│   └── *Response  # Output DTOs
├── converter/      # MapStruct mappers
├── constant/       # Enums and constants
└── util/          # Module-specific utilities
```

## Resources Structure

```
src/main/resources/
├── application.yml              # Main config
├── application-dev.yml          # Dev profile
├── application-test.yml         # Test profile
├── database/
│   ├── ddl/                    # Schema definitions
│   └── data/                   # Seed data
├── business_definition/         # YAML service orchestration configs
├── schemas/                     # Example JSON schemas
├── i18n.en-US.yaml             # English translations
└── i18n.zh-CN.yaml             # Chinese translations
```

## Naming Conventions

### Java Classes
- **Entity**: `ComponentRegistry`, `Menu`, `FileEntity` (extends `AbstractEntity`)
- **Mapper**: `ComponentRegistryMapper` (interface, extends MyBatis-Plus `BaseMapper`)
- **Service**: `ComponentRegistryService` (interface)
- **Service Impl**: `ComponentRegistryServiceImpl` (in `impl/` subfolder)
- **Controller**: `ComponentRegistryController` (annotated with `@RestController`)
- **DTO**: `ComponentRegistryDTO`, `ComponentRegistryCreateRequest`, `ComponentRegistryUpdateRequest`
- **Converter**: `ComponentRegistryConverter` (MapStruct interface)

### Database Tables
- Prefix: `ab_` (e.g., `ab_component_registry`, `ab_menu`)
- Naming: snake_case
- Common fields: `id`, `pid` (business key), `tenant_id`, `created_at`, `updated_at`, `deleted_flag`

### Packages
- All lowercase, no underscores
- Feature-based grouping preferred over layer-based

## Key Design Patterns

- **Multi-tenancy**: Tenant ID in all entities, filtered at data layer
- **Soft Delete**: `deleted_flag` boolean field, never hard delete
- **Audit Fields**: `created_at`, `updated_at`, `created_by`, `updated_by` in `AbstractEntity`
- **JSONB Storage**: Complex configurations stored as JSONB in PostgreSQL
- **Type Handlers**: Custom MyBatis type handlers for JSONB (e.g., `JsonbStringTypeHandler`)
- **API Response**: All endpoints return `ApiResponse<T>` wrapper
- **Service Facade**: Base service interfaces for orchestration
- **Feature Flags**: Feature vector system for permission management
