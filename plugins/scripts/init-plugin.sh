#!/bin/bash
#
# DEPRECATED: Use the CLI instead:
#   cd plugins/cli && npx tsx src/index.ts plugin init <name>
#   See: plugins/cli/README.md
#
# Scaffold script for AuraBoot Plugin
# Generates a plugin directory with template files ready for development.
#
# Usage:
#   ./init-plugin.sh <plugin-name> [options]
#
# Options:
#   -n, --namespace <ns>     Plugin namespace (default: auto-generated from name)
#   -d, --display-name <name> Display name in Chinese (default: plugin-name)
#   --full                   Include backend + frontend skeleton (PF4J + Module Federation)
#   -h, --help               Show this help message
#
# Examples:
#   ./init-plugin.sh safety-inspection
#   ./init-plugin.sh safety-inspection -n si -d "安全巡检"
#   ./init-plugin.sh safety-inspection --full
#

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PLUGINS_DIR="$(dirname "$SCRIPT_DIR")"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

log_info()  { echo -e "${GREEN}+${NC} $1"; }
log_error() { echo -e "${RED}✗${NC} $1"; }

usage() {
  echo "Usage: $0 <plugin-name> [options]"
  echo ""
  echo "Options:"
  echo "  -n, --namespace <ns>       Plugin namespace (default: initials of name)"
  echo "  -d, --display-name <name>  Display name in Chinese"
  echo "  --full                     Include backend + frontend skeleton"
  echo "  -h, --help                 Show this help"
  echo ""
  echo "Examples:"
  echo "  $0 safety-inspection"
  echo "  $0 safety-inspection -n si -d \"安全巡检\""
  echo "  $0 safety-inspection --full"
  exit 0
}

# Parse arguments
PLUGIN_NAME=""
NAMESPACE=""
DISPLAY_NAME=""
FULL_MODE=false

while [[ $# -gt 0 ]]; do
  case $1 in
    -n|--namespace)
      NAMESPACE="$2"
      shift 2
      ;;
    -d|--display-name)
      DISPLAY_NAME="$2"
      shift 2
      ;;
    --full)
      FULL_MODE=true
      shift
      ;;
    -h|--help)
      usage
      ;;
    -*)
      log_error "Unknown option: $1"
      exit 1
      ;;
    *)
      if [ -z "$PLUGIN_NAME" ]; then
        PLUGIN_NAME="$1"
      else
        log_error "Unexpected argument: $1"
        exit 1
      fi
      shift
      ;;
  esac
done

if [ -z "$PLUGIN_NAME" ]; then
  log_error "Plugin name is required"
  echo ""
  usage
fi

# Validate plugin name (lowercase, hyphens only)
if ! echo "$PLUGIN_NAME" | grep -qE '^[a-z][a-z0-9-]*$'; then
  log_error "Plugin name must be lowercase letters, numbers, and hyphens (e.g., safety-inspection)"
  exit 1
fi

# Auto-generate namespace from name initials if not provided
if [ -z "$NAMESPACE" ]; then
  NAMESPACE=$(echo "$PLUGIN_NAME" | sed 's/-/ /g' | awk '{for(i=1;i<=NF;i++) printf substr($i,1,1)}')
fi

# Default display name
if [ -z "$DISPLAY_NAME" ]; then
  DISPLAY_NAME="$PLUGIN_NAME"
fi

PLUGIN_ID="com.auraboot.$PLUGIN_NAME"
PLUGIN_DIR="$PLUGINS_DIR/$PLUGIN_NAME"

# Check if directory already exists
if [ -d "$PLUGIN_DIR" ]; then
  log_error "Directory already exists: $PLUGIN_DIR"
  exit 1
fi

echo -e "${BOLD}=== AuraBoot Plugin Scaffold ===${NC}"
echo ""
echo -e "  Name:       ${CYAN}$PLUGIN_NAME${NC}"
echo -e "  Plugin ID:  ${CYAN}$PLUGIN_ID${NC}"
echo -e "  Namespace:  ${CYAN}$NAMESPACE${NC}"
echo -e "  Display:    ${CYAN}$DISPLAY_NAME${NC}"
echo -e "  Mode:       ${CYAN}$([ "$FULL_MODE" = true ] && echo "Full (Config + Backend + Frontend)" || echo "Config-Only")${NC}"
echo ""

# Create directory structure
mkdir -p "$PLUGIN_DIR/config"

# ==================== plugin.json ====================
cat > "$PLUGIN_DIR/plugin.json" << PLUGIN_JSON
{
  "pluginId": "$PLUGIN_ID",
  "namespace": "$NAMESPACE",
  "version": "1.0.0",
  "displayName:zh-CN": "$DISPLAY_NAME",
  "displayName:en": "$PLUGIN_NAME",
  "description": "",
  "author": "AuraBoot Team",
  "homepage": "https://auraboot.com/plugins/$PLUGIN_NAME",
  "minPlatformVersion": "1.0.0",
  "dependencies": [],
  "resourceDirs": {
    "dicts": "config/dicts.json",
    "fields": "config/fields.json",
    "models": "config/models.json",
    "modelFieldBindings": "config/bindings.json",
    "commands": "config/commands.json",
    "permissions": "config/permissions.json",
    "roles": "config/roles.json",
    "menus": "config/menus.json",
    "pages": "config/pages.json"
  },
  "importOptions": {
    "conflictStrategy": "ERROR",
    "validateReferences": true,
    "autoDeployProcesses": false,
    "createResourcePermissions": false,
    "autoPublishPages": false
  }
}
PLUGIN_JSON
log_info "plugin.json"

# ==================== config/dicts.json ====================
cat > "$PLUGIN_DIR/config/dicts.json" << 'DICTS_JSON'
[
  {
    "code": "EXAMPLE_STATUS",
    "name:zh-CN": "示例状态",
    "name:en": "Example Status",
    "dictType": "STATIC",
    "items": [
      { "value": "DRAFT", "label:zh-CN": "草稿", "label:en": "Draft", "sortNo": 1 },
      { "value": "ACTIVE", "label:zh-CN": "生效", "label:en": "Active", "sortNo": 2 }
    ]
  }
]
DICTS_JSON
log_info "config/dicts.json"

# ==================== config/fields.json ====================
cat > "$PLUGIN_DIR/config/fields.json" << 'FIELDS_JSON'
[
  {
    "code": "example_name",
    "displayName:zh-CN": "名称",
    "displayName:en": "Name",
    "dataType": "STRING",
    "constraints": { "required": true, "maxLength": 200 }
  },
  {
    "code": "example_status",
    "displayName:zh-CN": "状态",
    "displayName:en": "Status",
    "dataType": "DICT",
    "dictCode": "EXAMPLE_STATUS",
    "constraints": { "required": true }
  }
]
FIELDS_JSON
log_info "config/fields.json"

# ==================== config/models.json ====================
NS_UPPER=$(echo "$NAMESPACE" | tr '[:lower:]' '[:upper:]')
MODEL_CODE="${NAMESPACE}-example"

cat > "$PLUGIN_DIR/config/models.json" << MODELS_JSON
[
  {
    "code": "$MODEL_CODE",
    "displayName:zh-CN": "示例模型",
    "displayName:en": "Example Model",
    "modelType": "ENTITY"
  }
]
MODELS_JSON
log_info "config/models.json"

# ==================== config/bindings.json ====================
cat > "$PLUGIN_DIR/config/bindings.json" << BINDINGS_JSON
[
  { "modelCode": "$MODEL_CODE", "fieldCode": "example_name", "sequence": 1, "required": true },
  { "modelCode": "$MODEL_CODE", "fieldCode": "example_status", "sequence": 2, "required": true }
]
BINDINGS_JSON
log_info "config/bindings.json"

# ==================== config/commands.json ====================
cat > "$PLUGIN_DIR/config/commands.json" << COMMANDS_JSON
[
  {
    "code": "${NS_UPPER}_CREATE_EXAMPLE",
    "displayName:zh-CN": "新建示例",
    "modelCode": "$MODEL_CODE",
    "type": "CREATE",
    "inputFields": ["example_name"],
    "autoSetFields": {
      "example_status": { "strategy": "FIXED_VALUE", "value": "DRAFT" }
    }
  },
  {
    "code": "${NS_UPPER}_UPDATE_EXAMPLE",
    "displayName:zh-CN": "更新示例",
    "modelCode": "$MODEL_CODE",
    "type": "UPDATE",
    "inputFields": ["example_name"]
  },
  {
    "code": "${NS_UPPER}_DELETE_EXAMPLE",
    "displayName:zh-CN": "删除示例",
    "modelCode": "$MODEL_CODE",
    "type": "DELETE"
  },
  {
    "code": "${NS_UPPER}_QUERY_EXAMPLE",
    "displayName:zh-CN": "查询示例",
    "modelCode": "$MODEL_CODE",
    "type": "QUERY"
  }
]
COMMANDS_JSON
log_info "config/commands.json"

# ==================== config/permissions.json ====================
cat > "$PLUGIN_DIR/config/permissions.json" << PERMISSIONS_JSON
[
  {
    "code": "${NS_UPPER}_MENU",
    "name:zh-CN": "$DISPLAY_NAME",
    "resourceType": "MENU",
    "action": "view"
  }
]
PERMISSIONS_JSON
log_info "config/permissions.json"

# ==================== config/roles.json ====================
cat > "$PLUGIN_DIR/config/roles.json" << ROLES_JSON
[
  {
    "code": "${NS_UPPER}_ADMIN",
    "name:zh-CN": "${DISPLAY_NAME}管理员",
    "name:en": "$PLUGIN_NAME Admin",
    "type": "CUSTOM",
    "permissions": ["${NS_UPPER}_MENU"]
  }
]
ROLES_JSON
log_info "config/roles.json"

# ==================== config/menus.json ====================
cat > "$PLUGIN_DIR/config/menus.json" << MENUS_JSON
[
  {
    "code": "${NS_UPPER}_MENU",
    "name:zh-CN": "$DISPLAY_NAME",
    "name:en": "$PLUGIN_NAME",
    "path": "/${PLUGIN_NAME}",
    "icon": "FileText",
    "type": 1,
    "parentCode": null,
    "permissionCode": "${NS_UPPER}_MENU",
    "modelCode": "$MODEL_CODE",
    "pageType": "list",
    "visible": true,
    "orderNo": 100
  }
]
MENUS_JSON
log_info "config/menus.json"

# ==================== config/pages.json ====================
cat > "$PLUGIN_DIR/config/pages.json" << 'PAGES_JSON'
[]
PAGES_JSON
log_info "config/pages.json"

# ==================== Full mode: backend + frontend ====================
if [ "$FULL_MODE" = true ]; then
  # Convert plugin-name to PascalCase for Java class names
  PASCAL_NAME=$(echo "$PLUGIN_NAME" | sed 's/-/ /g' | awk '{for(i=1;i<=NF;i++) $i=toupper(substr($i,1,1)) substr($i,2)}1' | tr -d ' ')
  PACKAGE_NAME=$(echo "$PLUGIN_NAME" | tr '-' '.')

  # Backend skeleton
  BACKEND_DIR="$PLUGIN_DIR/backend"
  JAVA_DIR="$BACKEND_DIR/src/main/java/com/auraboot/plugins/${PLUGIN_NAME//-/}"
  RESOURCES_DIR="$BACKEND_DIR/src/main/resources"

  mkdir -p "$JAVA_DIR/extension"
  mkdir -p "$RESOURCES_DIR"

  # build.gradle
  cat > "$BACKEND_DIR/build.gradle" << BUILD_GRADLE
plugins {
    id 'java'
}

group = 'com.auraboot.plugins'
version = '1.0.0'
sourceCompatibility = '21'

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    compileOnly 'com.auraboot:platform-plugin-api:1.0.0-SNAPSHOT'
    compileOnly 'org.pf4j:pf4j:3.11.1'
}

jar {
    manifest {
        attributes(
            'Plugin-Id': '$PLUGIN_ID',
            'Plugin-Version': '1.0.0',
            'Plugin-Class': 'com.auraboot.plugins.${PLUGIN_NAME//-/}.${PASCAL_NAME}Plugin'
        )
    }
}
BUILD_GRADLE
  log_info "backend/build.gradle"

  # settings.gradle
  cat > "$BACKEND_DIR/settings.gradle" << SETTINGS_GRADLE
rootProject.name = '${PLUGIN_NAME}-plugin'
SETTINGS_GRADLE

  # Plugin main class
  cat > "$JAVA_DIR/${PASCAL_NAME}Plugin.java" << PLUGIN_JAVA
package com.auraboot.plugins.${PLUGIN_NAME//-/};

import org.pf4j.Plugin;

public class ${PASCAL_NAME}Plugin extends Plugin {

    @Override
    public void start() {
        log.info("${PASCAL_NAME}Plugin started");
    }

    @Override
    public void stop() {
        log.info("${PASCAL_NAME}Plugin stopped");
    }
}
PLUGIN_JAVA
  log_info "backend/.../${PASCAL_NAME}Plugin.java"

  # plugin.properties
  cat > "$RESOURCES_DIR/plugin.properties" << PLUGIN_PROPS
plugin.id=$PLUGIN_ID
plugin.class=com.auraboot.plugins.${PLUGIN_NAME//-/}.${PASCAL_NAME}Plugin
plugin.version=1.0.0
plugin.provider=AuraBoot Team
plugin.description=$DISPLAY_NAME
PLUGIN_PROPS
  log_info "backend/.../plugin.properties"

  # Frontend skeleton
  FRONTEND_DIR="$PLUGIN_DIR/frontend"
  mkdir -p "$FRONTEND_DIR/src/components"
  mkdir -p "$FRONTEND_DIR/src/pages"

  # package.json
  cat > "$FRONTEND_DIR/package.json" << PACKAGE_JSON
{
  "name": "@auraboot/${PLUGIN_NAME}-plugin",
  "version": "1.0.0",
  "private": true,
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "vite build",
    "preview": "vite preview"
  },
  "dependencies": {
    "react": "^19.0.0",
    "react-dom": "^19.0.0"
  },
  "devDependencies": {
    "@types/react": "^19.0.0",
    "@types/react-dom": "^19.0.0",
    "@originjs/vite-plugin-federation": "^1.3.5",
    "typescript": "^5.7.0",
    "vite": "^6.0.0"
  }
}
PACKAGE_JSON
  log_info "frontend/package.json"

  # vite.config.ts
  cat > "$FRONTEND_DIR/vite.config.ts" << VITE_CONFIG
import { defineConfig } from 'vite';
import federation from '@originjs/vite-plugin-federation';

export default defineConfig({
  plugins: [
    federation({
      name: '${PLUGIN_NAME//-/_}',
      filename: 'remoteEntry.js',
      exposes: {
        // './ExamplePage': './src/pages/ExamplePage.tsx',
      },
      shared: ['react', 'react-dom'],
    }),
  ],
  build: {
    target: 'esnext',
    minify: false,
  },
});
VITE_CONFIG
  log_info "frontend/vite.config.ts"

  # tsconfig.json
  cat > "$FRONTEND_DIR/tsconfig.json" << TSCONFIG
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "ESNext",
    "moduleResolution": "bundler",
    "jsx": "react-jsx",
    "strict": true,
    "esModuleInterop": true,
    "skipLibCheck": true,
    "outDir": "dist"
  },
  "include": ["src"]
}
TSCONFIG
  log_info "frontend/tsconfig.json"

  # index.ts
  cat > "$FRONTEND_DIR/src/index.ts" << INDEX_TS
// Export components for Module Federation
// export { default as ExamplePage } from './pages/ExamplePage';
INDEX_TS
  log_info "frontend/src/index.ts"
fi

echo ""

# Run validation
echo -e "${BOLD}--- Running validation ---${NC}"
if "$SCRIPT_DIR/validate-plugin.sh" "$PLUGIN_DIR" 2>&1; then
  VALIDATION_OK=true
else
  VALIDATION_OK=false
fi

echo ""
echo -e "${BOLD}=== Scaffold Complete ===${NC}"
echo ""
echo "  Directory: $PLUGIN_DIR"
echo ""
echo "  Next steps:"
echo "    1. Edit config/*.json to define your models, fields, commands"
echo "    2. Run validation:  $SCRIPT_DIR/validate-plugin.sh $PLUGIN_DIR"
echo "    3. Build package:   $SCRIPT_DIR/build-plugin.sh $PLUGIN_DIR"
echo "    4. Import via API or admin UI"
echo ""
echo "  Reference docs:"
echo "    - docs/system-reference/24-插件ConfigOnly开发指南.md"
echo "    - docs/system-reference/05-Command系统.md"
echo "    - docs/system-reference/08-插件开发完整指南.md"
echo ""
