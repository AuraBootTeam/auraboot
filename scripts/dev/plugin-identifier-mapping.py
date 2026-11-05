#!/usr/bin/env python3
"""Pure request construction for explicit, per-plugin identifier migrations."""
import json
import re
import sys


def plan(global_path, selected, specifications):
    if global_path and specifications:
        raise ValueError("Global and per-plugin identifier mappings cannot overlap")
    if not isinstance(selected, dict) or any(not isinstance(k, str) or not isinstance(v, str) for k, v in selected.items()):
        raise ValueError("Invalid selected plugin roster")
    ids = list(selected.values())
    if len(ids) != len(set(ids)):
        raise ValueError("Ambiguous selected plugin identity")
    mappings = {}
    for specification in specifications:
        plugin, separator, path = specification.partition("=")
        if not separator or not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}", plugin):
            raise ValueError("Invalid per-plugin mapping identity")
        if not path.startswith("/") or any(ord(c) < 32 for c in path):
            raise ValueError("Mapping path must be absolute and contain no control characters")
        if plugin in mappings:
            raise ValueError("Duplicate per-plugin identifier mapping")
        if plugin not in ids:
            raise ValueError("Identifier mapping plugin is not selected")
        mappings[plugin] = path
    return {"global": global_path, "plugins": mappings, "selected": selected}


def request(path, mapping_plan):
    data = dict(path=path, conflictStrategy="OVERWRITE", autoPublishModels=True,
                autoPublishFields=True, autoPublishCommands=True, autoPublishPages=True,
                deferReferenceValidation=True)
    mappings = mapping_plan["plugins"]
    if mappings and path not in mapping_plan["selected"]:
        raise ValueError("Unsealed selected plugin path")
    mapping = mappings.get(mapping_plan["selected"].get(path), mapping_plan["global"])
    if mapping:
        data["identifierMappingPath"] = mapping
    return data


if __name__ == "__main__":
    try:
        if sys.argv[1] == "plan":
            result = plan(sys.argv[2], json.loads(sys.argv[3]), sys.argv[4:])
        elif sys.argv[1] == "request":
            result = request(sys.argv[2], json.loads(sys.argv[3]))
        else:
            raise ValueError("Unknown mapping operation")
        print(json.dumps(result, ensure_ascii=False))
    except (ValueError, KeyError, IndexError) as error:
        print("ERROR: " + str(error), file=sys.stderr)
        sys.exit(2)
