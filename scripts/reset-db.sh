#!/bin/bash

# AuraBoot Database Reset Script
# This script drops and recreates the aura_boot database

set -e

DB_NAME="aura_boot"
DB_USER="ghj"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
SCHEMA_FILE="$PROJECT_ROOT/platform/src/main/resources/database/schema.sql"

echo "=== AuraBoot Database Reset ==="
echo ""

# Check if schema file exists
if [ ! -f "$SCHEMA_FILE" ]; then
    echo "Error: Schema file not found at $SCHEMA_FILE"
    exit 1
fi

# Confirm before proceeding
read -p "This will DELETE all data in '$DB_NAME'. Continue? (y/N): " confirm
if [[ "$confirm" != "y" && "$confirm" != "Y" ]]; then
    echo "Aborted."
    exit 0
fi

echo ""
echo "Step 1: Terminating existing connections to '$DB_NAME'..."
psql -d postgres -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '$DB_NAME' AND pid <> pg_backend_pid();" 2>/dev/null || true

echo "Step 2: Dropping database '$DB_NAME'..."
psql -d postgres -c "DROP DATABASE IF EXISTS $DB_NAME;"

echo "Step 3: Creating database '$DB_NAME'..."
psql -d postgres -c "CREATE DATABASE $DB_NAME;"

echo "Step 4: Initializing schema..."
psql -d "$DB_NAME" -f "$SCHEMA_FILE"

echo ""
echo "=== Database reset complete! ==="
