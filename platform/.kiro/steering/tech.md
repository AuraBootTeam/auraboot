---
inclusion: always
---

# Technology Stack

## Core Framework
- **Java 21** - Language version with toolchain configuration
- **Spring Boot 3.5.6** - Application framework
- **Gradle** - Build system with Groovy DSL

## Key Dependencies

### Data Layer
- **MyBatis-Plus 3.5.14** - ORM framework extending MyBatis
- **PostgreSQL 42.7.8** - Primary database (required)
- **HikariCP** - Connection pooling (via Spring Boot)

### Security & Auth
- **Spring Security** - Authentication and authorization
- **JWT (jjwt 0.12.6)** - Token-based authentication
- **Custom Password Encoder** - Password hashing

### Utilities
- **Lombok 1.18.40** - Boilerplate reduction (requires annotation processing)
- **MapStruct 1.5.5** - Bean mapping (requires annotation processing)
- **Jackson 2.17.2** - JSON processing with YAML support
- **ULID** - Unique identifier generation (de.huxhorn.sulky)
- **AspectJ 1.9.22** - AOP support

### API & Documentation
- **SpringDoc OpenAPI 2.2.0** - API documentation (Swagger UI)
- **Spring GraphQL** - GraphQL support

### Monitoring
- **Spring Actuator** - Health checks and metrics
- **Sentry 6.28.0** - Error tracking

## Common Commands

### Build & Run
```bash
# Build the project
./gradlew build

# Run the application
./gradlew bootRun

# Run tests
./gradlew test

# Clean build artifacts
./gradlew clean

# Build JAR without tests
./gradlew bootJar -x test
```

### Database
- Database: PostgreSQL (required, not H2 or MySQL)
- Default connection: `jdbc:postgresql://localhost:5432/aura_boot`
- Schema scripts: `src/main/resources/database/ddl/`
- Data scripts: `src/main/resources/database/data/`

### Development
- Main class: `com.auraboot.framework.application.MetaApplication`
- Active profile: `dev` (see `application-dev.yml`)
- Server port: `6443`
- Hot reload: Spring DevTools enabled

## Code Generation

The project uses annotation processors:
- **Lombok** - Generates getters, setters, constructors, etc.
- **MapStruct** - Generates mapper implementations

Ensure your IDE has annotation processing enabled.

## Database Conventions

- **ID Type**: BIGINT (Long in Java) - configurable via `database.id-type`
- **Snowflake IDs**: Distributed ID generation (worker-id and datacenter-id configurable)
- **ULID**: Alternative unique identifier format
- **Soft Delete**: `deleted_flag` field (boolean)
- **Timestamps**: Use `TIMESTAMPTZ` in PostgreSQL, stored in UTC
- **JSONB**: Extensive use for flexible schema storage (requires custom type handlers)
- **Naming**: Snake_case in database, camelCase in Java (auto-converted by MyBatis-Plus)

## Important Notes

- Encoding: UTF-8 for all source files
- Compiler args: `-parameters` flag enabled (required for Spring parameter name discovery)
- Multi-tenant: Tenant ID isolation at data layer
- Time zone: Always UTC in database
