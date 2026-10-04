#!/usr/bin/env python3
"""Pack every public OSS package and the private QR client; never publish."""

import argparse
import hashlib
import fnmatch
import json
from pathlib import Path
import subprocess
import tarfile
import shutil

# Whitespace-normalized authoritative text from
# https://www.apache.org/licenses/LICENSE-2.0.txt (verified 2026-10-02).
APACHE_TEXT_SHA256 = "0ffddef9e48f8a09aed5caf2d44f7ba1c1be2d9b8e0a6f693b1635b2d5566645"


def normalized(text):
    return " ".join(text.split())


def validate_canonical(text):
    if hashlib.sha256(normalized(text).encode()).hexdigest() != APACHE_TEXT_SHA256:
        raise ValueError("canonical license differs from the authoritative Apache-2.0 text")


def validate_archive(path, canonical, expected):
    with tarfile.open(path, "r:gz") as archive:
        files = {m.name: m for m in archive.getmembers() if m.isfile()}
        def read(name):
            if name not in files:
                raise ValueError(f"missing {name}")
            return archive.extractfile(files[name]).read().decode("utf-8")
        metadata = json.loads(read("package/package.json"))
        if metadata["name"] != expected["name"] or metadata["version"] != expected["version"]:
            raise ValueError("package identity mismatch")
        if metadata.get("license") != "Apache-2.0":
            raise ValueError("metadata license mismatch")
        if metadata.get("private", False) != expected.get("private", False):
            raise ValueError("publication boundary changed")
        if normalized(read("package/LICENSE")) != normalized(canonical):
            raise ValueError("package license is not the canonical Apache-2.0 text")
        for name in files:
            if "license" in Path(name).name.lower() and name != "package/LICENSE":
                raise ValueError(f"unexpected competing license: {name}")
        if "Apache-2.0" not in read("package/README.md"):
            raise ValueError("README license missing")
        targets = []
        def collect(value):
            if isinstance(value, str) and value.startswith("./"):
                targets.append(value)
            elif isinstance(value, dict):
                for item in value.values():
                    collect(item)
        for key in ("exports", "main", "types"):
            collect(metadata.get(key))
        if not targets:
            raise ValueError("no package entry points")
        for target in targets:
            pattern = "package/" + target[2:]
            if not any(fnmatch.fnmatchcase(name, pattern) for name in files):
                raise ValueError(f"missing exported entry point: {target}")
        return {"name": metadata["name"], "version": metadata["version"],
                "license": metadata["license"], "private": metadata.get("private", False),
                "files": sorted(files), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--enterprise-root", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    core = Path(__file__).resolve().parents[1]
    canonical = (core / "packages/open-platform-sdk/LICENSE").read_text()
    validate_canonical(canonical)
    packages = [p.parent for p in sorted((core / "packages").glob("*/package.json"))
                if json.loads(p.read_text()).get("private") is not True]
    packages.append(args.enterprise_root.resolve() / "packages/auraqr-sdk")
    required = {"designer-sdk", "dsl-runtime", "dsl-types", "nav-model", "plugin-sdk",
                "open-platform-sdk", "ui", "web-testkit", "auraqr-sdk"}
    if not required.issubset({p.name for p in packages}):
        raise ValueError("package inventory shrank below the approved nine-package baseline")
    if json.loads((packages[-1] / "package.json").read_text()).get("private") is not True:
        raise ValueError("AuraQR SDK must remain private")
    for name, absent_section, patent_section, no_section_ten in (
        ("LICENSE-FAQ-en.md", "### Q11.", "### Q19.", "There is no §10"),
        ("LICENSE-FAQ.md", "### Q11.", "### Q19.", "协议没有 §10"),
    ):
        faq = (core / name).read_text()
        if not all(package in faq for package in required):
            raise ValueError(f"{name}: package license inventory is incomplete")
        q11 = faq.split(absent_section, 1)[1].split("### Q12.", 1)[0]
        if "ambiguous" in q11 or "边界开始模糊" in q11:
            raise ValueError(f"{name}: retired SaaS restriction remains")
        q19 = faq.split(patent_section, 1)[1].split("### Q20.", 1)[0]
        if "§3" not in q19 or no_section_ten not in q19:
            raise ValueError(f"{name}: patent license citation correction missing")
    args.out.mkdir(parents=True, exist_ok=False)
    results = []
    for package in packages:
        expected = json.loads((package / "package.json").read_text())
        dest = args.out / package.name
        dest.mkdir()
        packed = subprocess.run(["pnpm", "pack", "--pack-destination", str(dest.resolve())],
                                cwd=package, capture_output=True, text=True)
        (dest / "pack.log").write_text(packed.stdout + packed.stderr)
        (dest / "pack.exit").write_text(str(packed.returncode) + "\n")
        if packed.returncode:
            raise RuntimeError(f"pack failed for {expected['name']}; see {dest / 'pack.log'}")
        archives = list(dest.glob("*.tgz"))
        if len(archives) != 1:
            raise ValueError(f"expected one fresh tarball for {expected['name']}")
        result = validate_archive(archives[0], canonical, expected)
        result["archive"] = str(archives[0].resolve())
        results.append(result)
    # Install actual tarballs outside both repositories, without registry dependencies.
    consumer = args.out / "consumer"
    consumer.mkdir()
    (consumer / "package.json").write_text('{"private":true,"type":"module"}\n')
    sdk = next(result for result in results if result["name"] == "@auraboot/open-platform-sdk")
    qr = next(result for result in results if result["name"] == "@auraboot/auraqr-sdk")
    for name in ("consumer.test.mjs", "consumer-ts-check.ts"):
        shutil.copyfile(core / "scripts/fixtures/open-platform-consumer" / name, consumer / name)
    commands = {
        "install": ["npm", "install", "--offline", "--ignore-scripts", "--no-audit", "--no-fund", sdk["archive"], qr["archive"]],
        "runtime": ["node", "consumer.test.mjs"],
        "types": [str(core / "node_modules/.bin/tsc"), "--strict", "--noEmit", "--module", "nodenext", "--target", "es2022", "consumer-ts-check.ts"],
        "qr-import": ["node", "--input-type=module", "-e", "import assert from 'node:assert/strict'; import {AuraQrClient} from '@auraboot/auraqr-sdk'; assert.equal(typeof AuraQrClient, 'function');"],
    }
    for name, command in commands.items():
        ran = subprocess.run(command, cwd=consumer, capture_output=True, text=True)
        (consumer / f"{name}.log").write_text(ran.stdout + ran.stderr)
        (consumer / f"{name}.exit").write_text(str(ran.returncode) + "\n")
        if ran.returncode:
            raise RuntimeError(f"clean consumer {name} failed; see {consumer / (name + '.log')}")
        if name == "runtime" and "SUMMARY pass=9 fail=0" not in ran.stdout:
            raise ValueError("consumer fixture did not execute the full nine-case contract")
    # Create only after every package and consumer has passed. No stale success receipt is reused.
    (args.out / "receipt.json").write_text(json.dumps({"publish": "not_executed",
        "source": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=core, text=True).strip(),
        "dirty": bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=core)),
        "enterpriseSource": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=args.enterprise_root, text=True).strip(),
        "enterpriseDirty": bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=args.enterprise_root)),
        "packages": results, "consumer": {"cases": 9, "provider": "loopback-fixture",
        "types": "pass", "qrImport": "pass"}}, indent=2) + "\n")
    print(f"Validated {len(results)} real tarballs; publication not executed.")


if __name__ == "__main__":
    main()
