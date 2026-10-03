#!/usr/bin/env python3
"""Freeze prewarmed, named image identities before an admitted release build."""
import json
import re
import subprocess
import sys
from pathlib import Path


def repository(reference):
    name = reference.split("@", 1)[0]
    if ":" in name.rsplit("/", 1)[-1]:
        name = name.rsplit(":", 1)[0]
    first = name.split("/", 1)[0]
    if "." not in first and ":" not in first and first != "localhost":
        name = "docker.io/" + ("library/" if "/" not in name else "") + name
    return name


def freeze_image(tag, record):
    repo = repository(tag)
    image_id = record.get("Id", "")
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", image_id):
        raise ValueError(f"invalid prewarmed image ID: {tag}")
    refs = set()
    for ref in record.get("RepoDigests") or []:
        name, separator, digest = ref.partition("@")
        if separator and repository(name) == repo:
            if not re.fullmatch(r"sha256:[0-9a-f]{64}", digest):
                raise ValueError(f"invalid repository digest: {tag}")
            refs.add(repo + "@" + digest)
    if len(refs) != 1:
        raise ValueError(f"expected one digest for the configured repository: {tag}")
    ref = refs.pop()
    if "@" in tag and tag.split("@", 1)[1] != ref.split("@", 1)[1]:
        raise ValueError(f"configured immutable digest mismatch: {tag}")
    return {"tag": tag, "reference": ref, "imageId": image_id}


def main():
    output, *tags = sys.argv[1:]
    if not tags:
        raise ValueError("no configured base images")
    images = []
    for tag in tags:
        raw = subprocess.check_output(["docker", "image", "inspect", tag], text=True)
        records = json.loads(raw)
        if len(records) != 1:
            raise ValueError(f"expected one prewarmed image: {tag}")
        images.append(freeze_image(tag, records[0]))
    Path(output).write_text(json.dumps({"images": images}, indent=2) + "\n")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, subprocess.CalledProcessError) as error:
        print(f"release base image freeze: {error}", file=sys.stderr)
        sys.exit(2)
