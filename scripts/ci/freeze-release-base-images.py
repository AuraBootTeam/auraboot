#!/usr/bin/env python3
"""Freeze prewarmed, named image identities before an admitted release build."""
import json
import os
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



def load_transports(raw):
    """A transport can select another registry, but never different image bytes."""
    def unique_object(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("duplicate base image transport source")
            result[key] = value
        return result
    if not isinstance(raw, str) or len(raw) > 65536:
        raise ValueError("base image transport input exceeds budget")
    mapping = json.loads(raw, object_pairs_hook=unique_object)
    if not isinstance(mapping, dict) or len(mapping) > 32:
        raise ValueError("bounded base image transport map required")
    return mapping


def checked_transport(source, transport):
    if not isinstance(transport, str) or not re.fullmatch(
        r"[a-z0-9][a-z0-9.-]*(?::[0-9]{1,5})?/[a-z0-9][a-z0-9._/-]*@sha256:[0-9a-f]{64}", transport):
        raise ValueError("immutable registry transport reference required")
    name, _, digest = transport.partition("@")
    if repository(name) != name or ".." in name or "//" in name or name.endswith("/"):
        raise ValueError("explicit normalized registry transport required")
    if digest != source.partition("@")[2]:
        raise ValueError("base image transport digest differs from frozen source")
    return transport


def freeze_prewarmed(tag, mapping, inspect):
    if "@" not in tag:
        return freeze_image(tag, inspect(tag))
    source = repository(tag) + "@" + tag.partition("@")[2]
    transport = checked_transport(source, mapping.get(source, source))
    image = freeze_image(transport, inspect(transport))
    # An explicit canonical digest authorizes transport selection before inspection.
    # The selected registry must report that exact digest; named tags keep canonical authority.
    return dict(image, tag=tag, reference=source)


def bind_transports(images, raw):
    mapping = load_transports(raw)
    sources = {image["reference"] for image in images}
    if not set(mapping).issubset(sources):
        raise ValueError("base image transport source is not in the frozen manifest")
    bound = []
    for image in images:
        source = image["reference"]
        transport = mapping.get(source, source)
        transport = checked_transport(source, transport)
        bound.append(dict(image, transportReference=transport))
    return bound


def main():
    output, *tags = sys.argv[1:]
    if not tags:
        raise ValueError("no configured base images")
    raw_transports = os.environ.get("AURA_CI_RELEASE_BASE_IMAGE_TRANSPORTS", "{}")
    mapping = load_transports(raw_transports)
    def inspect(reference):
        records = json.loads(subprocess.check_output(["docker", "image", "inspect", reference], text=True))
        if len(records) != 1:
            raise ValueError(f"expected one prewarmed image: {reference}")
        return records[0]
    images = [freeze_prewarmed(tag, mapping, inspect) for tag in tags]
    images = bind_transports(images, raw_transports)
    Path(output).write_text(json.dumps({"images": images}, indent=2) + "\n")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, subprocess.CalledProcessError) as error:
        print(f"release base image freeze: {error}", file=sys.stderr)
        sys.exit(2)
