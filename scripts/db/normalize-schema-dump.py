#!/usr/bin/env python3
"""Normalize server/session metadata only; preserve all application DDL for drift comparison."""
import re
import sys

# PostgreSQL attribute-version safety metadata is server-local, not a DDL option.
# Upstream: postgres/postgres@245eb832f88631c986a2dadad2cbe94f57089fd6.
internal_attribute_version = re.compile(r"^ALTER TABLE ONLY .+ ALTER COLUMN .+ SET \(pg_attver\s*=\s*[0-9]+\);$")
volatile_header = re.compile(r"^(?:\\(?:un)?restrict |SET transaction_timeout = |-- Dumped (?:from database|by pg_dump) version )")
for line in sys.stdin:
    if volatile_header.match(line) or internal_attribute_version.fullmatch(line.rstrip("\r\n")):
        continue
    sys.stdout.write(line)
