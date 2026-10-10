"""Hermetic falsifiability checks for package archive validation."""
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("distribution", Path(__file__).with_name("check-package-distribution.py"))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)
LICENSE = Path(__file__).resolve().parents[1].joinpath("packages/open-platform-sdk/LICENSE").read_text()


class ArchiveContract(unittest.TestCase):
    def test_canonical_text_is_independent(self):
        gate.validate_canonical(LICENSE)
        with self.assertRaisesRegex(ValueError, "authoritative"):
            gate.validate_canonical(LICENSE.replace("perpetual", "temporary"))

    def check(self, changes=None, omitted=()):
        metadata = {"name": "@auraboot/fixture", "version": "1.0.0", "license": "Apache-2.0",
                    "exports": {".": {"import": "./dist/index.js", "types": "./dist/index.d.ts"}}}
        files = {"package/package.json": json.dumps(metadata), "package/LICENSE": LICENSE,
                 "package/README.md": "Apache-2.0", "package/dist/index.js": "export {};",
                 "package/dist/index.d.ts": "export {};"}
        files.update(changes or {})
        for name in omitted:
            del files[name]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "fixture.tgz"
            with tarfile.open(path, "w:gz") as archive:
                for name, content in files.items():
                    raw = content.encode()
                    item = tarfile.TarInfo(name)
                    item.size = len(raw)
                    archive.addfile(item, io.BytesIO(raw))
            return gate.validate_archive(path, LICENSE, metadata)

    def test_valid_archive(self):
        self.assertEqual(self.check()["license"], "Apache-2.0")

    def test_missing_license(self):
        with self.assertRaisesRegex(ValueError, "missing package/LICENSE"):
            self.check(omitted=["package/LICENSE"])

    def test_retired_license(self):
        with self.assertRaisesRegex(ValueError, "canonical"):
            self.check({"package/LICENSE": "AuraBoot License Agreement, Version 1.3"})

    def test_competing_license(self):
        with self.assertRaisesRegex(ValueError, "competing license"):
            self.check({"package/LICENSE.txt": "commercial restrictions"})

    def test_wrong_metadata(self):
        with self.assertRaisesRegex(ValueError, "metadata license mismatch"):
            self.check({"package/package.json": json.dumps({"name": "@auraboot/fixture", "version": "1.0.0", "license": "LicenseRef-AuraBoot-1.3"})})

    def test_missing_dist(self):
        with self.assertRaisesRegex(ValueError, "missing exported entry"):
            self.check(omitted=["package/dist/index.js"])

    def test_readme_mismatch(self):
        with self.assertRaisesRegex(ValueError, "README license missing"):
            self.check({"package/README.md": "Proprietary only"})

    def test_identity_mismatch(self):
        with self.assertRaisesRegex(ValueError, "identity mismatch"):
            self.check({"package/package.json": '{"name":"wrong", "version":"1.0.0"}'})

    def test_publication_boundary(self):
        with self.assertRaisesRegex(ValueError, "publication boundary"):
            self.check({"package/package.json": '{"name":"@auraboot/fixture", "version":"1.0.0", "license":"Apache-2.0", "private":true}'})


class ConsumerExecutionContract(unittest.TestCase):
    def output(self, rows):
        return "CONSUMER_RESULTS " + json.dumps(rows)

    def rows(self):
        return [{"name": f"case-{index}", "status": "passed"} for index in range(14)]

    def test_actual_execution_ledger(self):
        self.assertEqual(len(gate.validate_consumer_output(self.output(self.rows()))), 14)

    def test_summary_cannot_replace_execution(self):
        with self.assertRaisesRegex(ValueError, "execution ledger"):
            gate.validate_consumer_output("SUMMARY pass=14 fail=0")

    def test_empty_missing_and_duplicate_cases(self):
        for rows in [[], self.rows()[:-1], self.rows()[:-1] + [self.rows()[0]]]:
            with self.subTest(rows=rows):
                with self.assertRaisesRegex(ValueError, "fourteen distinct"):
                    gate.validate_consumer_output(self.output(rows))

    def test_failed_and_skipped_cases_are_rejected(self):
        for status in ["failed", "skipped", "did_not_run"]:
            rows = self.rows(); rows[-1]["status"] = status
            with self.assertRaisesRegex(ValueError, "incomplete or failed"):
                gate.validate_consumer_output(self.output(rows))


if __name__ == "__main__":
    unittest.main()
