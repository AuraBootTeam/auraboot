# Backend Plugin Guide

This guide covers building backend plugins for AuraBoot using PF4J. Backend plugins let you extend the platform with custom Java logic -- command handlers, validators, event listeners, and data providers -- that gets hot-loaded at runtime without restarting the server.

---

## When You Need a Backend Plugin

Config-only plugins handle ~80% of use cases. You need a backend plugin when:

- **Custom command handlers**: Business logic that cannot be expressed through DSL configuration (complex calculations, multi-step operations, external API calls)
- **Custom validators**: Validation rules that require database lookups or external checks
- **Event listeners**: React to platform events asynchronously (send notifications, update external systems)
- **Custom data providers**: Provide data for dropdowns or lookups from external sources
- **External integrations**: Email, SMS, payment gateways, ERP systems

---

## Architecture

Backend plugins are standard PF4J plugins. The platform discovers and loads them at runtime:

```
your-plugin.jar
  |
  +-- META-INF/
  |   +-- extensions.idx          # PF4J extension index
  |
  +-- com/example/plugin/
      +-- MyPlugin.java           # extends AuraPlugin
      +-- SendEmailHandler.java   # implements CommandHandlerExtension
      +-- OrderValidator.java     # implements ValidatorExtension
```

The platform provides four extension point interfaces:

| Interface | Purpose |
|-----------|---------|
| `CommandHandlerExtension` | Custom command processing logic |
| `ValidatorExtension` | Custom field/record validation |
| `EventListenerExtension` | Subscribe to platform events |
| `DataProviderExtension` | Provide data for lookups/dropdowns |

---

## Project Setup

### Gradle Build File

Create a new Gradle project for your plugin:

**`build.gradle.kts`**:

```kotlin
plugins {
    java
}

group = "com.example"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    // AuraBoot plugin API (provided at runtime by the platform)
    compileOnly("com.auraboot:auraboot-core:1.0.0-SNAPSHOT")

    // PF4J (provided at runtime)
    compileOnly("org.pf4j:pf4j:3.13.0")

    // Lombok (optional, for convenience)
    compileOnly("org.projectlombok:lombok:1.18.32")
    annotationProcessor("org.projectlombok:lombok:1.18.32")

    // Test dependencies
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

tasks.jar {
    manifest {
        attributes(
            "Plugin-Id" to "com.example.email-notifier",
            "Plugin-Version" to version,
            "Plugin-Class" to "com.example.plugin.EmailNotifierPlugin",
            "Plugin-Provider" to "Example Corp",
            "Plugin-Dependencies" to ""
        )
    }
}
```

**Important**: AuraBoot core is `compileOnly` because the platform provides it at runtime. Do not bundle it into your JAR.

### Plugin Main Class

Every PF4J plugin needs a main class that extends `AuraPlugin`:

**`src/main/java/com/example/plugin/EmailNotifierPlugin.java`**:

```java
package com.example.plugin;

import com.auraboot.framework.plugin.pf4j.AuraPlugin;
import com.auraboot.framework.plugin.api.*;
import lombok.extern.slf4j.Slf4j;
import org.pf4j.PluginWrapper;

@Slf4j
public class EmailNotifierPlugin extends AuraPlugin {

    public EmailNotifierPlugin(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public String getNamespace() {
        return "email";
    }

    @Override
    protected void doInstall(PluginInstallContext context) throws Exception {
        log.info("Email Notifier plugin installed for tenant {}", context.getTenantId());
    }

    @Override
    protected void doEnable(PluginEnableContext context) throws Exception {
        log.info("Email Notifier plugin enabled");
    }

    @Override
    protected void doDisable(PluginDisableContext context) throws Exception {
        log.info("Email Notifier plugin disabled");
    }

    @Override
    protected void doUninstall(PluginUninstallContext context) throws Exception {
        log.info("Email Notifier plugin uninstalled");
    }
}
```

---

## Implementing CommandHandlerExtension

The most common extension point. A command handler processes a specific command type with custom Java logic.

### Interface

```java
public interface CommandHandlerExtension extends ExtensionPoint {
    String getCommandType();
    Object execute(CommandContext context) throws Exception;
}
```

### Complete Example: Send Email Handler

**`src/main/java/com/example/plugin/SendEmailHandler.java`**:

```java
package com.example.plugin;

import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import lombok.extern.slf4j.Slf4j;
import org.pf4j.Extension;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;

/**
 * Custom command handler that sends an email notification
 * when a specific command is executed.
 *
 * Usage in DSL command config:
 * {
 *   "code": "myapp:send_notification",
 *   "type": "custom",
 *   "handlerType": "email:send-notification",
 *   ...
 * }
 */
@Slf4j
@Extension
public class SendEmailHandler implements CommandHandlerExtension {

    private static final String COMMAND_TYPE = "email:send-notification";
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Override
    public String getCommandType() {
        return COMMAND_TYPE;
    }

    @Override
    public Object execute(CommandContext context) throws Exception {
        log.info("Executing send-notification for tenant={}, model={}, record={}",
                context.tenantId(), context.modelCode(), context.recordId());

        // Extract data from the command payload
        Map<String, Object> payload = context.payload();
        String recipientEmail = (String) payload.get("recipient_email");
        String subject = (String) payload.get("subject");
        String body = (String) payload.get("body");

        if (recipientEmail == null || recipientEmail.isBlank()) {
            throw new IllegalArgumentException("recipient_email is required");
        }

        // Read plugin settings (configured per-tenant)
        Map<String, Object> settings = context.settings();
        String smtpEndpoint = (String) settings.getOrDefault("smtpEndpoint",
                "https://api.example.com/send");
        String apiKey = (String) settings.getOrDefault("apiKey", "");

        // Send the email via external API
        String jsonBody = String.format(
            "{\"to\":\"%s\",\"subject\":\"%s\",\"body\":\"%s\"}",
            recipientEmail, subject != null ? subject : "Notification", body != null ? body : ""
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(smtpEndpoint))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

        // Return result
        Map<String, Object> result = new HashMap<>();
        result.put("sent", response.statusCode() == 200);
        result.put("statusCode", response.statusCode());
        result.put("recipientEmail", recipientEmail);

        if (response.statusCode() != 200) {
            log.warn("Email send failed: status={}, body={}",
                    response.statusCode(), response.body());
            result.put("error", response.body());
        }

        log.info("Email notification sent to {}: status={}", recipientEmail, response.statusCode());
        return result;
    }

    @Override
    public int getPriority() {
        return 0; // Default priority
    }
}
```

### CommandContext Fields

The `CommandContext` record provides all the information your handler needs:

| Field | Type | Description |
|-------|------|-------------|
| `tenantId` | `Long` | Current tenant ID |
| `pluginId` | `String` | Plugin ID |
| `namespace` | `String` | Plugin namespace |
| `commandType` | `String` | The command type being executed |
| `modelCode` | `String` | Target model code |
| `recordId` | `String` | Target record ID (for update/state_transition) |
| `payload` | `Map<String, Object>` | Command input data |
| `settings` | `Map<String, Object>` | Plugin settings (per-tenant configuration) |

The context also provides accessor methods:
- `context.dataAccessor()` -- access to the platform's data layer for reading/writing records
- `context.biTemporalAccessor()` -- access to bi-temporal data (if the module is available)

---

## Implementing ValidatorExtension

Custom validators check field values or records against rules that cannot be expressed in DSL.

### Complete Example: Business Email Validator

**`src/main/java/com/example/plugin/BusinessEmailValidator.java`**:

```java
package com.example.plugin;

import com.auraboot.framework.plugin.extension.ValidatorExtension;
import org.pf4j.Extension;

import java.util.Set;

/**
 * Validates that an email address belongs to an approved business domain.
 *
 * Usage in field config:
 * {
 *   "code": "customer_email",
 *   "ruleSchema": {
 *     "validationRules": [
 *       {
 *         "type": "custom",
 *         "validator": "email:business-domain",
 *         "params": { "allowedDomains": "example.com,corp.com" }
 *       }
 *     ]
 *   }
 * }
 */
@Extension
public class BusinessEmailValidator implements ValidatorExtension {

    @Override
    public String getValidatorKey() {
        return "email:business-domain";
    }

    @Override
    public ValidationResult validate(ValidationContext context) {
        Object value = context.value();
        if (value == null) {
            return ValidationResult.success(); // Let required check handle nulls
        }

        String email = value.toString().toLowerCase();

        // Get allowed domains from validator params
        String domainsParam = (String) context.validatorParams()
                .getOrDefault("allowedDomains", "");

        if (domainsParam.isBlank()) {
            return ValidationResult.success(); // No restriction if no domains configured
        }

        Set<String> allowedDomains = Set.of(domainsParam.split(","));

        // Extract domain from email
        int atIndex = email.indexOf('@');
        if (atIndex < 0) {
            return ValidationResult.error(context.fieldCode(),
                    "Invalid email format");
        }

        String domain = email.substring(atIndex + 1);
        if (!allowedDomains.contains(domain)) {
            return ValidationResult.error(context.fieldCode(),
                    "Email domain '" + domain + "' is not in the approved list");
        }

        return ValidationResult.success();
    }
}
```

---

## Implementing EventListenerExtension

Event listeners subscribe to platform events and run logic asynchronously.

### Complete Example: Audit Log Listener

**`src/main/java/com/example/plugin/AuditLogListener.java`**:

```java
package com.example.plugin;

import com.auraboot.framework.plugin.extension.EventListenerExtension;
import lombok.extern.slf4j.Slf4j;
import org.pf4j.Extension;

import java.util.Set;

/**
 * Listens to record creation and update events for audit logging.
 */
@Slf4j
@Extension
public class AuditLogListener implements EventListenerExtension {

    @Override
    public Set<String> getSubscribedEvents() {
        // Subscribe to all creation and update events
        return Set.of("record:created", "record:updated", "record:deleted");
    }

    @Override
    public void onEvent(EventContext context) {
        log.info("Audit: tenant={}, event={}, model={}, record={}, data={}",
                context.tenantId(),
                context.eventType(),
                context.sourceModel(),
                context.recordId(),
                context.eventData()
        );

        // In a real implementation, you would write to an audit log table
        // or send to an external audit service
    }

    @Override
    public boolean isAsync() {
        return true; // Run asynchronously to not block the main operation
    }

    @Override
    public int getOrder() {
        return 200; // Run after other listeners
    }
}
```

**Event patterns:**

| Pattern | Matches |
|---------|---------|
| `record:created` | All record creation events |
| `record:*` | All record events (created, updated, deleted) |
| `*:created` | All creation events |
| `*` | All events |

---

## Implementing DataProviderExtension

Data providers supply data for dropdowns, lookups, or custom queries.

### Complete Example: Currency Provider

**`src/main/java/com/example/plugin/CurrencyDataProvider.java`**:

```java
package com.example.plugin;

import com.auraboot.framework.plugin.extension.DataProviderExtension;
import org.pf4j.Extension;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Provides a list of currencies for dropdown fields.
 *
 * Usage: Reference provider key "email:currencies" in field config.
 */
@Extension
public class CurrencyDataProvider implements DataProviderExtension {

    private static final List<DataItem> CURRENCIES = List.of(
        new DataItem("usd", "US Dollar", Map.of("symbol", "$", "code", "USD")),
        new DataItem("eur", "Euro", Map.of("symbol", "\u20ac", "code", "EUR")),
        new DataItem("gbp", "British Pound", Map.of("symbol", "\u00a3", "code", "GBP")),
        new DataItem("jpy", "Japanese Yen", Map.of("symbol", "\u00a5", "code", "JPY")),
        new DataItem("cny", "Chinese Yuan", Map.of("symbol", "\u00a5", "code", "CNY"))
    );

    @Override
    public String getProviderKey() {
        return "email:currencies";
    }

    @Override
    public List<DataItem> fetchData(DataRequest request) {
        List<DataItem> results = CURRENCIES;

        // Apply search filter
        if (request.searchTerm() != null && !request.searchTerm().isBlank()) {
            String term = request.searchTerm().toLowerCase();
            results = results.stream()
                    .filter(item -> item.label().toLowerCase().contains(term)
                            || item.value().toLowerCase().contains(term))
                    .collect(Collectors.toList());
        }

        // Apply pagination
        int start = Math.min(request.offset(), results.size());
        int end = Math.min(start + request.limit(), results.size());
        return results.subList(start, end);
    }

    @Override
    public boolean isCacheable() {
        return true;
    }

    @Override
    public int getCacheTtlSeconds() {
        return 3600; // Cache for 1 hour
    }
}
```

---

## Build and Package

### Build the JAR

```bash
./gradlew build
```

The JAR is created at `build/libs/email-notifier-1.0.0.jar`.

### JAR Manifest Requirements

The JAR's `MANIFEST.MF` must contain PF4J metadata:

```
Plugin-Id: com.example.email-notifier
Plugin-Version: 1.0.0
Plugin-Class: com.example.plugin.EmailNotifierPlugin
Plugin-Provider: Example Corp
Plugin-Dependencies:
```

These are set in the Gradle build file's `tasks.jar` block.

### Deploy

**Option 1: Directory-based (development)**

Place the JAR in the platform's plugin directory:

```
platform/plugins/
  +-- com.example.email-notifier/
      +-- email-notifier-1.0.0.jar
```

PF4J auto-discovers it on startup.

**Option 2: Unified package (production)**

Package the JAR as part of a unified plugin package:

```
email-notifier-plugin.zip
  +-- plugin.json
  +-- config/             # Optional: DSL config
  +-- backend/
      +-- email-notifier-1.0.0.jar
```

Install via API:
```bash
POST /api/plugins/packages/install
Content-Type: multipart/form-data
file: email-notifier-plugin.zip
```

---

## Testing

### Unit Testing Extensions

```java
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SendEmailHandlerTest {

    private final SendEmailHandler handler = new SendEmailHandler();

    @Test
    void getCommandType_returnsCorrectType() {
        assertEquals("email:send-notification", handler.getCommandType());
    }

    @Test
    void supports_matchesCommandType() {
        assertTrue(handler.supports("email:send-notification"));
        assertFalse(handler.supports("other:command"));
    }

    @Test
    void execute_throwsOnMissingRecipient() {
        var context = CommandHandlerExtension.CommandContext.builder()
                .tenantId(1L)
                .pluginId("com.example.email-notifier")
                .namespace("email")
                .commandType("email:send-notification")
                .modelCode("test_model")
                .payload(Map.of())  // No recipient_email
                .settings(Map.of())
                .build();

        assertThrows(IllegalArgumentException.class, () -> handler.execute(context));
    }
}
```

### Integration Testing

For integration tests against a running platform, use the platform's `BaseIntegrationTest`:

```java
@SpringBootTest
@ActiveProfiles("integration-test")
class SendEmailHandlerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private PluginManagerService pluginManager;

    @Test
    void pluginLoadsAndHandlerRegisters() {
        // Verify the plugin is loaded
        var extensions = pluginManager.getExtensions(CommandHandlerExtension.class);
        assertTrue(extensions.stream()
                .anyMatch(e -> "email:send-notification".equals(e.getCommandType())));
    }
}
```

---

## Accessing Platform Services

Within your extension, you can access platform data through the `CommandContext`:

```java
@Override
public Object execute(CommandContext context) throws Exception {
    // Read records from the platform's data layer
    DataAccessor dataAccessor = context.dataAccessor();
    if (dataAccessor != null) {
        Map<String, Object> record = dataAccessor.getById(
                context.modelCode(), context.recordId());
        // Use record data...
    }

    // Access bi-temporal data (if available)
    BiTemporalAccessor bta = context.biTemporalAccessor();
    if (bta != null) {
        // Query historical data...
    }

    return result;
}
```

---

## Best Practices

1. **Keep JARs small**: Only include your plugin code. Platform dependencies are `compileOnly`.
2. **Use `@Extension` annotation**: PF4J uses this to build the extension index at compile time.
3. **Handle errors gracefully**: Throw meaningful exceptions with clear messages. The platform's global exception handler translates them to API responses.
4. **Use structured logging**: Log with `tenantId`, `modelCode`, `recordId` context for troubleshooting.
5. **Make handlers idempotent**: The same command might be retried. Design for safe re-execution.
6. **Use settings for configuration**: Put API keys, endpoints, and tunables in plugin settings, not hardcoded values.

---

## Next Steps

- [Config-Only Plugin Tutorial](./config-only-plugin.md) -- if your logic can be expressed in DSL
- [Frontend Plugin Guide](./frontend-plugin.md) -- if you also need custom UI components
- [Full-Stack Plugin Guide](./full-stack-plugin.md) -- combining config + backend + frontend
- [Plugin Manifest Reference](./plugin-manifest-reference.md) -- complete plugin.json schema
