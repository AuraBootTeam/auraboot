import importlib.util
import json
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("base_images", Path(__file__).with_name("freeze-release-base-images.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class BaseImageIdentityTest(unittest.TestCase):
    def record(self, digests):
        return {"Id": "sha256:" + "a" * 64, "RepoDigests": digests}

    def test_official_repository_normalization(self):
        for tag, repo in [("redis:7.4-alpine", "docker.io/library/redis"),
                          ("pgvector/pgvector:pg16", "docker.io/pgvector/pgvector")]:
            result = module.freeze_image(tag, self.record([repo + "@sha256:" + "b" * 64]))
            self.assertEqual(result["reference"], repo + "@sha256:" + "b" * 64)
            self.assertEqual(result["imageId"], "sha256:" + "a" * 64)

    def test_foreign_mirror_digest_is_not_source_authority(self):
        with self.assertRaises(ValueError):
            module.freeze_image("redis:7.4-alpine", self.record(["untrusted.example/redis@sha256:" + "b" * 64]))

    def test_missing_ambiguous_or_invalid_digest_fails_closed(self):
        for digests in [[], ["redis@sha256:bad"],
                        ["redis@sha256:" + "b" * 64, "redis@sha256:" + "c" * 64]]:
            with self.assertRaises(ValueError):
                module.freeze_image("redis:7.4-alpine", self.record(digests))

    def test_explicit_immutable_input_cannot_change_digest(self):
        with self.assertRaises(ValueError):
            module.freeze_image("redis@sha256:" + "c" * 64, self.record(["redis@sha256:" + "b" * 64]))

    def test_controlled_registry_keeps_its_repository_authority(self):
        tag = "controlled.example/team/redis@sha256:" + "b" * 64
        self.assertEqual(module.freeze_image(tag, self.record([tag]))["reference"], tag)
        image = module.freeze_image(tag, self.record([tag]))
        transport = "mirror.example/cache/redis@sha256:" + "b" * 64
        bound = module.bind_transports([image], json.dumps({tag: transport}))[0]
        self.assertEqual(bound["reference"], tag)
        self.assertEqual(bound["transportReference"], transport)
        self.assertEqual(bound["imageId"], image["imageId"])
        self.assertEqual(module.bind_transports([image], "{}")[0]["transportReference"], tag)
        bad_maps = [{tag: transport.replace("b" * 64, "c" * 64)},
                    {tag: "mirror.example/cache/redis:latest"}, {"unknown": transport},
                    {tag: "https://" + transport}, {tag: None}, [],
                    {tag: transport.replace("cache/", "../")},
                    {tag: transport.replace("cache/", "cache//")},
                    {tag: transport.replace("mirror.example", "mirror")},
                    {tag: transport.replace("@sha256:", "?token=private@sha256:")}]
        for mapping in bad_maps:
            with self.subTest(mapping=mapping), self.assertRaises(ValueError):
                module.bind_transports([image], json.dumps(mapping))
        with self.assertRaises(ValueError):
            module.bind_transports([image], json.dumps({tag: transport})[:-1] + ',"' + tag + '":"' + transport + '"}')
        with self.assertRaises(ValueError):
            module.bind_transports([image], " " * 65537)


if __name__ == "__main__":
    unittest.main()
