# Full-Stack Plugin Guide

This guide walks through building a complete full-stack plugin that combines config (DSL), backend (PF4J Java), and frontend (Module Federation React) into a single distributable package. The example builds a **Document Approval** plugin.

---

## When to Build Full-Stack

Build a full-stack plugin when you need all three layers working together:

- **Config layer**: Data models, commands, pages, menus, permissions
- **Backend layer**: Custom command handlers with business logic (approval workflow, email notifications, external API calls)
- **Frontend layer**: Custom UI components (document viewer, approval timeline, rich status indicators)

If you only need one or two layers, see:
- [Config-Only Plugin](./config-only-plugin.md) -- JSON only
- [Backend Plugin](./backend-plugin.md) -- Java only
- [Frontend Plugin](./frontend-plugin.md) -- React only

---

## Architecture: How the Three Layers Connect

```
+----------------------------------------------------------------+
|                   Document Approval Plugin                      |
|                                                                |
|  Config Layer (JSON)          Backend Layer (JAR)              |
|  +----------------------+    +----------------------------+    |
|  | models.json          |    | ApprovalPlugin.java        |    |
|  |   da_document        |    |   extends AuraPlugin       |    |
|  |   da_approval_log    |    |                            |    |
|  |                      |    | ApproveHandler.java        |    |
|  | commands/            |    |   implements               |    |
|  |   da:create_document |    |   CommandHandlerExtension  |    |
|  |   da:submit_document |    |                            |    |
|  |   da:approve_document|<---| ApprovalNotifier.java      |    |
|  |                      |    |   implements               |    |
|  | pages/               |    |   EventListenerExtension   |    |
|  |   da_document_list   |    +----------------------------+    |
|  |   da_document_form   |                                      |
|  |   da_document_detail |    Frontend Layer (React)            |
|  +----------------------+    +----------------------------+    |
|                              | DocumentViewer.tsx          |    |
|                              |   Custom block renderer     |    |
|                              |                            |    |
|                              | ApprovalTimeline.tsx       |    |
|                              |   Custom widget             |    |
|                              +----------------------------+    |
+----------------------------------------------------------------+
```

**How they connect:**
1. The **config layer** defines the data model (`da_document`) and a `state_transition` command (`da:approve_document`)
2. The command references a **backend handler** (`da:approve-handler`) that runs custom approval logic and sends email notifications
3. The **frontend layer** provides a `document-viewer` block type used in the detail page schema to render document previews

---

## Example: Document Approval Plugin

### Project Structure

```
plugins/document-approval/
+-- plugin.json
+-- config/
|   +-- models.json
|   +-- fields/
|   |   +-- da_document.json
|   +-- bindings/
|   |   +-- da_document.json
|   +-- commands/
|   |   +-- da_document.json
|   +-- pages/
|   |   +-- da_document_list.json
|   |   +-- da_document_form.json
|   |   +-- da_document_detail.json
|   +-- dicts.json
|   +-- permissions.json
|   +-- roles.json
|   +-- menus.json
|   +-- i18n.json
|   +-- default-bootstrap.json
+-- backend/
|   +-- document-approval-1.0.0.jar
+-- frontend/
    +-- remoteEntry.js
    +-- assets/
        +-- index-xxx.js
```

### Plugin Manifest

**`plugin.json`**:

```json
{
  "pluginId": "com.example.document-approval",
  "namespace": "da",
  "version": "1.0.0",
  "dslVersion": 1,
  "pluginType": "hybrid",
  "displayName": "Document Approval",
  "displayName:zh-CN": "Document Approval",
  "displayName:en": "Document Approval",
  "description": "Document submission and approval workflow with email notifications and document preview.",
  "author": "AuraBoot Community",
  "minPlatformVersion": "1.0.0",
  "dependencies": [],
  "provides": [
    { "type": "model", "code": "da_document" },
    { "type": "command", "code": "da:create_document" },
    { "type": "command", "code": "da:update_document" },
    { "type": "command", "code": "da:delete_document" },
    { "type": "command", "code": "da:submit_document" },
    { "type": "command", "code": "da:approve_document" },
    { "type": "command", "code": "da:reject_document" }
  ],
  "resourceDirs": {
    "models": "config/models.json",
    "fields": "config/fields",
    "bindings": "config/bindings",
    "commands": "config/commands",
    "pages": "config/pages",
    "dicts": "config/dicts.json",
    "permissions": "config/permissions.json",
    "roles": "config/roles.json",
    "menus": "config/menus.json",
    "i18n": "config/i18n.json"
  },
  "importOptions": {
    "conflictStrategy": "overwrite",
    "validateReferences": true,
    "autoPublishModels": true,
    "autoPublishFields": true,
    "autoPublishCommands": true,
    "autoPublishPages": true,
    "createResourcePermissions": true
  },
  "backend": {
    "jar": "backend/document-approval-1.0.0.jar",
    "pluginClass": "com.example.docapproval.DocumentApprovalPlugin"
  },
  "frontend": {
    "remoteEntry": "frontend/remoteEntry.js",
    "modules": {
      "blocks": {
        "document-viewer": {
          "module": "./DocumentViewer",
          "name": "Document Viewer",
          "description": "Renders a document preview with metadata"
        }
      }
    }
  }
}
```

---

## Config Layer

The config layer follows the same patterns as a config-only plugin. Here are the key files:

### Model

**`config/models.json`**:

```json
[
  {
    "code": "da_document",
    "displayName:zh-CN": "Document",
    "displayName:en": "Document",
    "description": "A document that goes through an approval workflow",
    "modelType": "entity",
    "modelCategory": "entity",
    "extension": {
      "icon": "FileText",
      "category": "document-approval",
      "titleField": "da_title",
      "subtitleField": "da_code"
    }
  }
]
```

### Fields

**`config/fields/da_document.json`**:

```json
[
  {
    "code": "da_title",
    "displayName:en": "Title",
    "dataType": "string",
    "constraints": { "required": true, "maxLength": 200 },
    "feature": { "searchable": true, "sortable": true }
  },
  {
    "code": "da_code",
    "displayName:en": "Code",
    "dataType": "string",
    "constraints": { "required": true, "maxLength": 50 },
    "feature": { "searchable": true }
  },
  {
    "code": "da_content",
    "displayName:en": "Content",
    "dataType": "text",
    "extension": { "renderComponent": "richtext" }
  },
  {
    "code": "da_status",
    "displayName:en": "Status",
    "dataType": "enum",
    "dictCode": "da_status_dict",
    "defaultValue": "draft",
    "feature": { "searchable": true, "sortable": true }
  },
  {
    "code": "da_author",
    "displayName:en": "Author",
    "dataType": "string",
    "constraints": { "maxLength": 100 }
  },
  {
    "code": "da_submitted_at",
    "displayName:en": "Submitted At",
    "dataType": "datetime",
    "extension": { "readOnly": true }
  },
  {
    "code": "da_approved_at",
    "displayName:en": "Approved At",
    "dataType": "datetime",
    "extension": { "readOnly": true }
  },
  {
    "code": "da_approver_comment",
    "displayName:en": "Approver Comment",
    "dataType": "text"
  },
  {
    "code": "da_attachment",
    "displayName:en": "Attachment",
    "dataType": "string",
    "extension": { "renderComponent": "file-upload" }
  },
  {
    "code": "da_created_at",
    "displayName:en": "Created At",
    "dataType": "datetime",
    "extension": { "readOnly": true }
  }
]
```

### Commands (with Custom Handler Reference)

**`config/commands/da_document.json`**:

```json
[
  {
    "code": "da:create_document",
    "displayName:en": "Create Document",
    "type": "create",
    "modelCode": "da_document",
    "inputFields": ["da_title", "da_content", "da_author", "da_attachment"],
    "autoSetFields": {
      "da_code": { "strategy": "auto_generate", "pattern": "DOC-{yyyyMMdd}-{seq}" },
      "da_created_at": { "strategy": "current_datetime" },
      "da_status": { "strategy": "fixed_value", "value": "draft" }
    },
    "permissions": ["da.document.manage"],
    "cmd_risk_level": "L1"
  },
  {
    "code": "da:update_document",
    "displayName:en": "Update Document",
    "type": "update",
    "modelCode": "da_document",
    "inputFields": ["da_title", "da_content", "da_author", "da_attachment"],
    "permissions": ["da.document.manage"],
    "cmd_risk_level": "L1"
  },
  {
    "code": "da:delete_document",
    "displayName:en": "Delete Document",
    "type": "delete",
    "modelCode": "da_document",
    "preconditions": [
      { "field": "da_status", "operator": "IN", "value": ["draft", "rejected"] }
    ],
    "permissions": ["da.document.manage"],
    "cmd_risk_level": "L4"
  },
  {
    "code": "da:submit_document",
    "displayName:en": "Submit for Approval",
    "type": "state_transition",
    "modelCode": "da_document",
    "stateField": "da_status",
    "fromStates": ["draft", "rejected"],
    "toState": "pending",
    "autoSetFields": {
      "da_submitted_at": { "strategy": "current_datetime" }
    },
    "permissions": ["da.document.manage"],
    "cmd_risk_level": "L1"
  },
  {
    "code": "da:approve_document",
    "displayName:en": "Approve",
    "type": "state_transition",
    "modelCode": "da_document",
    "stateField": "da_status",
    "fromStates": ["pending"],
    "toState": "approved",
    "inputFields": ["da_approver_comment"],
    "autoSetFields": {
      "da_approved_at": { "strategy": "current_datetime" }
    },
    "permissions": ["da.document.approve"],
    "cmd_risk_level": "L2"
  },
  {
    "code": "da:reject_document",
    "displayName:en": "Reject",
    "type": "state_transition",
    "modelCode": "da_document",
    "stateField": "da_status",
    "fromStates": ["pending"],
    "toState": "rejected",
    "inputFields": ["da_approver_comment"],
    "permissions": ["da.document.approve"],
    "cmd_risk_level": "L2"
  }
]
```

### Dictionaries, Permissions, Menus

**`config/dicts.json`**:

```json
[
  {
    "code": "da_status_dict",
    "name": "Document Status",
    "dictType": "static",
    "items": [
      { "value": "draft", "label": "Draft", "sortNo": 10, "extension": { "color": "gray" } },
      { "value": "pending", "label": "Pending Approval", "sortNo": 20, "extension": { "color": "gold" } },
      { "value": "approved", "label": "Approved", "sortNo": 30, "extension": { "color": "green" } },
      { "value": "rejected", "label": "Rejected", "sortNo": 40, "extension": { "color": "red" } }
    ]
  }
]
```

**`config/permissions.json`**:

```json
[
  { "code": "da.document.manage", "name:en": "Manage Documents", "resourceType": "operation", "module": "da" },
  { "code": "da.document.approve", "name:en": "Approve Documents", "resourceType": "operation", "module": "da" },
  { "code": "da.document.read", "name:en": "View Documents", "resourceType": "data", "module": "da" }
]
```

**`config/menus.json`**:

```json
[
  {
    "code": "da_root",
    "parentCode": null,
    "name:en": "Documents",
    "icon": "IconFileText",
    "type": 0,
    "orderNo": 15,
    "visible": true
  },
  {
    "code": "da_document_list_menu",
    "parentCode": "da_root",
    "name:en": "All Documents",
    "path": "/p/da_document",
    "icon": "IconFiles",
    "type": 1,
    "permissionCode": "da.document.read",
    "orderNo": 1,
    "visible": true,
    "pageKey": "da_document_list"
  }
]
```

**`config/default-bootstrap.json`**:

```json
{
  "rolePermissionBindings": [
    { "roleCode": "tenant_admin", "permissionCodes": ["*"] }
  ]
}
```

---

## Backend Layer

### Custom Approval Handler

The backend handles the approval logic and sends email notifications via an external API.

**`backend/src/main/java/com/example/docapproval/DocumentApprovalPlugin.java`**:

```java
package com.example.docapproval;

import com.auraboot.framework.plugin.pf4j.AuraPlugin;
import com.auraboot.framework.plugin.api.*;
import org.pf4j.PluginWrapper;

public class DocumentApprovalPlugin extends AuraPlugin {

    public DocumentApprovalPlugin(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public String getNamespace() {
        return "da";
    }

    @Override
    protected void doInstall(PluginInstallContext context) {}

    @Override
    protected void doEnable(PluginEnableContext context) {}

    @Override
    protected void doDisable(PluginDisableContext context) {}

    @Override
    protected void doUninstall(PluginUninstallContext context) {}
}
```

**`backend/src/main/java/com/example/docapproval/ApprovalNotifier.java`**:

```java
package com.example.docapproval;

import com.auraboot.framework.plugin.extension.EventListenerExtension;
import lombok.extern.slf4j.Slf4j;
import org.pf4j.Extension;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Set;

/**
 * Listens for document approval/rejection events and sends email notifications.
 */
@Slf4j
@Extension
public class ApprovalNotifier implements EventListenerExtension {

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Override
    public Set<String> getSubscribedEvents() {
        return Set.of("record:updated");
    }

    @Override
    public void onEvent(EventContext context) {
        // Only process da_document model
        if (!"da_document".equals(context.sourceModel())) {
            return;
        }

        Map<String, Object> data = context.eventData();
        Map<String, Object> previous = context.previousData();

        if (data == null || previous == null) {
            return;
        }

        String newStatus = String.valueOf(data.get("da_status"));
        String oldStatus = String.valueOf(previous.get("da_status"));

        // Only send notification on status changes to approved/rejected
        if (newStatus.equals(oldStatus)) {
            return;
        }

        if ("approved".equals(newStatus) || "rejected".equals(newStatus)) {
            String author = String.valueOf(data.getOrDefault("da_author", ""));
            String title = String.valueOf(data.getOrDefault("da_title", "Untitled"));
            String comment = String.valueOf(data.getOrDefault("da_approver_comment", ""));

            sendNotification(author, title, newStatus, comment);
        }
    }

    private void sendNotification(String recipient, String docTitle, String status, String comment) {
        log.info("Sending {} notification to {} for document '{}'", status, recipient, docTitle);

        // In production, call a real email/notification API here
        // This is a placeholder that logs the notification
        try {
            String payload = String.format(
                "{\"to\":\"%s\",\"subject\":\"Document %s: %s\",\"body\":\"Your document '%s' has been %s.%s\"}",
                recipient, status, docTitle, docTitle, status,
                comment.isEmpty() ? "" : " Comment: " + comment
            );

            log.info("Notification payload: {}", payload);
        } catch (Exception e) {
            log.error("Failed to send notification", e);
        }
    }

    @Override
    public boolean isAsync() {
        return true; // Don't block the main approval operation
    }
}
```

### Build the Backend JAR

```bash
cd backend
./gradlew build
cp build/libs/document-approval-1.0.0.jar ../backend/
```

---

## Frontend Layer

### Custom Document Viewer Component

**`frontend/src/blocks/DocumentViewer.tsx`**:

```tsx
import React from 'react';

interface DocumentViewerProps {
  block: {
    id: string;
    blockType: string;
    contentField: string;
    attachmentField: string;
    showMetadata?: boolean;
  };
  record: Record<string, any>;
  modelCode: string;
}

const DocumentViewer: React.FC<DocumentViewerProps> = ({ block, record }) => {
  const content = record?.[block.contentField] || '';
  const attachment = record?.[block.attachmentField];
  const showMetadata = block.showMetadata !== false;

  return (
    <div
      data-testid={`block-${block.id}`}
      style={{
        border: '1px solid #e8e8e8',
        borderRadius: 8,
        overflow: 'hidden',
      }}
    >
      {/* Document content preview */}
      <div
        style={{
          padding: 24,
          minHeight: 300,
          backgroundColor: '#fff',
          fontSize: 14,
          lineHeight: 1.8,
        }}
        dangerouslySetInnerHTML={{ __html: content }}
      />

      {/* Attachment indicator */}
      {attachment && (
        <div
          style={{
            padding: '12px 24px',
            borderTop: '1px solid #f0f0f0',
            backgroundColor: '#fafafa',
            display: 'flex',
            alignItems: 'center',
            gap: 8,
            fontSize: 13,
            color: '#666',
          }}
        >
          <span>Attachment: {attachment}</span>
        </div>
      )}

      {/* Metadata footer */}
      {showMetadata && (
        <div
          style={{
            padding: '8px 24px',
            borderTop: '1px solid #f0f0f0',
            backgroundColor: '#fafafa',
            display: 'flex',
            gap: 24,
            fontSize: 12,
            color: '#999',
          }}
        >
          {record.da_author && <span>Author: {record.da_author}</span>}
          {record.da_submitted_at && <span>Submitted: {record.da_submitted_at}</span>}
          {record.da_approved_at && <span>Approved: {record.da_approved_at}</span>}
        </div>
      )}
    </div>
  );
};

export default DocumentViewer;
```

### Vite Config

**`frontend/vite.config.ts`**:

```typescript
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import federation from '@originjs/vite-plugin-federation';

export default defineConfig({
  plugins: [
    react(),
    federation({
      name: 'docApprovalPlugin',
      filename: 'remoteEntry.js',
      exposes: {
        './DocumentViewer': './src/blocks/DocumentViewer.tsx',
      },
      shared: {
        react: { singleton: true, requiredVersion: '^18.0.0' },
        'react-dom': { singleton: true, requiredVersion: '^18.0.0' },
      },
    }),
  ],
  build: {
    target: 'esnext',
    minify: true,
  },
});
```

### Build the Frontend

```bash
cd frontend
npx vite build
```

---

## Build, Package, Deploy

### 1. Build all layers

```bash
# Build backend
cd backend && ./gradlew build
cp build/libs/document-approval-1.0.0.jar ../backend/

# Build frontend
cd ../frontend && npx vite build
```

### 2. Validate the config

```bash
aura plugin validate plugins/document-approval
```

### 3. Publish the config layer

```bash
aura plugin publish plugins/document-approval --yes
```

### 4. Install the backend JAR

Place the JAR in the platform's plugin directory for PF4J to discover:

```bash
cp backend/document-approval-1.0.0.jar \
   platform/plugins/com.example.document-approval/
```

The platform hot-loads the JAR on the next scan cycle (or restart).

### 5. Deploy the frontend

For development, serve via a local dev server. For production, the frontend assets are served from the plugin package.

### 6. Verify

```bash
# Check plugin status
aura status

# Verify model and commands
aura dsl show da_document

# Create a test document
aura exec da:create_document \
  --set da_title="Q1 Report" \
  --set da_author="admin@auraboot.com" \
  --set da_content="<p>Quarterly results...</p>"

# Submit for approval
aura exec da:submit_document --target <recordPid>

# Approve (triggers email notification via backend handler)
aura exec da:approve_document --target <recordPid> \
  --set da_approver_comment="Looks good, approved."
```

---

## How the Layers Interact at Runtime

```
User clicks "Approve" button in UI
    |
    v
Frontend sends POST /api/meta/commands/execute/da:approve_document
    |
    v
Platform's CommandExecutor processes the command
    |
    +-- 1. Validates preconditions (status must be "pending")
    +-- 2. Runs autoSetFields (sets da_approved_at)
    +-- 3. Executes state transition (pending -> approved)
    +-- 4. Saves record to database
    +-- 5. Publishes "record:updated" event
           |
           v
    PF4J EventListenerExtension receives event
           |
           v
    ApprovalNotifier.onEvent() runs asynchronously
           |
           +-- Checks if status changed to "approved"
           +-- Sends email notification to document author
    |
    v
Frontend receives success response
    |
    +-- Detail page re-renders with updated status
    +-- DocumentViewer block shows approval timestamp
    +-- Toast notification: "Document approved"
```

---

## Next Steps

- [Config-Only Plugin Tutorial](./config-only-plugin.md) -- start here for simpler plugins
- [Backend Plugin Guide](./backend-plugin.md) -- deep dive into PF4J extensions
- [Frontend Plugin Guide](./frontend-plugin.md) -- deep dive into Module Federation
- [Plugin Manifest Reference](./plugin-manifest-reference.md) -- complete plugin.json schema
