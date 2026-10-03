"""Tests for release.py; CI runs them with `python3 -m unittest discover -s .github/scripts`."""

import contextlib
import io
import json
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

import release

VERSION_TYPES = [
    {"id": 1, "name": "Minecraft 26", "slug": "minecraft-26"},
    {"id": 2, "name": "Minecraft 1.21", "slug": "minecraft-1-21"},
    {"id": 3, "name": "Modloader", "slug": "modloader"},
    {"id": 4, "name": "Java", "slug": "java"},
    {"id": 5, "name": "Environment", "slug": "environment"},
    {"id": 6, "name": "Addons", "slug": "addons"},
]

VERSIONS = [
    {"id": 101, "gameVersionTypeID": 1, "name": "26.2", "slug": "26-2"},
    {"id": 102, "gameVersionTypeID": 1, "name": "26.3", "slug": "26-3"},
    {"id": 103, "gameVersionTypeID": 1, "name": "26.3-Snapshot", "slug": "26-3-snapshot"},
    {"id": 104, "gameVersionTypeID": 2, "name": "1.21.11", "slug": "1-21-11"},
    {"id": 105, "gameVersionTypeID": 6, "name": "26.3", "slug": "26-3"},
    {"id": 201, "gameVersionTypeID": 3, "name": "Forge", "slug": "forge"},
    {"id": 202, "gameVersionTypeID": 3, "name": "NeoForge", "slug": "neoforge"},
    {"id": 301, "gameVersionTypeID": 4, "name": "Java 21", "slug": "java-21"},
    {"id": 302, "gameVersionTypeID": 4, "name": "Java 25", "slug": "java-25"},
    {"id": 401, "gameVersionTypeID": 5, "name": "Client", "slug": "client"},
    {"id": 402, "gameVersionTypeID": 5, "name": "Server", "slug": "server"},
]

CHANGELOG = """# Changelog

## 0.2.0 (unreleased)

- Radiation.

## [0.1.0-alpha.1] - 2026-10-04

The first alpha.

- Heat.

## 0.0.1

"""


class ReleaseTest(unittest.TestCase):

    def test_pre_release_labels_set_the_release_type(self):
        self.assertEqual("alpha", release.release_type("0.1.0-alpha.1"))
        self.assertEqual("beta", release.release_type("0.4.0-beta"))
        self.assertEqual("beta", release.release_type("1.0.0-rc.2"))
        self.assertEqual("release", release.release_type("1.0.0"))
        self.assertEqual("release", release.release_type("2.0.0-source"), "only the label's start counts")

    def test_a_tag_must_name_the_version_and_publishes_it(self):
        self.assertEqual({"version": "0.1.0-alpha.1", "release_type": "alpha", "publish": "true"},
                         release.check("tag", "v0.1.0-alpha.1", "0.1.0-alpha.1"))
        with self.assertRaises(release.ReleaseError):
            release.check("tag", "v0.1.0", "0.1.0-alpha.1")
        self.assertEqual("false", release.check("branch", "main", "0.1.0-alpha.1")["publish"])

    def test_notes_come_from_the_versions_changelog_section(self):
        self.assertEqual("The first alpha.\n\n- Heat.\n", release.notes(CHANGELOG, "0.1.0-alpha.1"))
        self.assertEqual("- Radiation.\n", release.notes(CHANGELOG, "0.2.0"))
        with self.assertRaises(release.ReleaseError):
            release.notes(CHANGELOG, "0.0.1")
        with self.assertRaises(release.ReleaseError):
            release.notes(CHANGELOG, "0.1.0")

    def test_game_versions_name_minecraft_the_loader_java_and_both_sides(self):
        self.assertEqual([102, 202, 302, 401, 402],
                         release.game_version_ids(VERSION_TYPES, VERSIONS, "26.3", "NeoForge", "25"))
        without_java = [v for v in VERSIONS if v["id"] != 302]
        self.assertEqual([102, 202, 401, 402],
                         release.game_version_ids(VERSION_TYPES, without_java, "26.3", "NeoForge", "25"))
        with self.assertRaises(release.ReleaseError):
            release.game_version_ids(VERSION_TYPES, VERSIONS, "26.4", "NeoForge", "25")
        with self.assertRaises(release.ReleaseError):
            release.game_version_ids(VERSION_TYPES, VERSIONS, "26.3", "Quilt", "25")

    def test_multipart_bodies_carry_the_fields_and_the_file(self):
        content_type, body = release.multipart({"metadata": '{"a": 1}'}, "file", "anchor.jar", b"PK\x03\x04")
        boundary = content_type.split("boundary=", 1)[1]
        self.assertTrue(body.startswith(f"--{boundary}\r\n".encode()))
        self.assertIn(b'name="metadata"\r\n\r\n{"a": 1}\r\n', body)
        self.assertIn(b'filename="anchor.jar"', body)
        self.assertIn(b"\r\n\r\nPK\x03\x04\r\n", body)
        self.assertTrue(body.endswith(f"\r\n--{boundary}--\r\n".encode()))

    def test_without_a_token_curseforge_is_skipped(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            uploaded = release.curseforge("anchor.jar", "notes.md", True,
                                          {"curseforge_project_id": "123456", "mod_version": "0.1.0"}, "")
        self.assertIsNone(uploaded)
        self.assertIn("CURSEFORGE_TOKEN", out.getvalue())

    def test_an_upload_sends_the_jar_with_its_metadata(self):
        received = {}

        class FakeCurseForge(BaseHTTPRequestHandler):
            def do_GET(self):
                received.setdefault("tokens", []).append(self.headers["X-Api-Token"])
                answer = VERSION_TYPES if self.path == "/api/game/version-types" else VERSIONS
                self.reply(answer)

            def do_POST(self):
                received["path"] = self.path
                received["type"] = self.headers["Content-Type"]
                received["body"] = self.rfile.read(int(self.headers["Content-Length"]))
                self.reply({"id": 7})

            def reply(self, answer):
                data = json.dumps(answer).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def log_message(self, *args):
                pass

        server = HTTPServer(("127.0.0.1", 0), FakeCurseForge)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        original = release.CURSEFORGE
        release.CURSEFORGE = f"http://127.0.0.1:{server.server_port}"
        try:
            with tempfile.TemporaryDirectory() as tmp:
                jar = Path(tmp, "anchor-0.1.0-alpha.1.jar")
                jar.write_bytes(b"PK jar bytes")
                notes = Path(tmp, "notes.md")
                notes.write_text("- Heat at 20 \u00b0C.\n", encoding="utf-8")
                props = {"curseforge_project_id": "123456", "mod_version": "0.1.0-alpha.1",
                         "minecraft_version": "26.3", "mod_name": "Anchor"}
                with contextlib.redirect_stdout(io.StringIO()):
                    uploaded = release.curseforge(str(jar), str(notes), True, props, "secret-token")
        finally:
            release.CURSEFORGE = original
            server.shutdown()
            server.server_close()
        self.assertEqual(7, uploaded)
        self.assertEqual(["secret-token", "secret-token"], received["tokens"])
        self.assertEqual("/api/projects/123456/upload-file", received["path"])
        self.assertTrue(received["type"].startswith("multipart/form-data; boundary="))
        self.assertIn(b"PK jar bytes", received["body"])
        metadata = json.loads(received["body"].split(b'name="metadata"\r\n\r\n', 1)[1].split(b"\r\n", 1)[0])
        self.assertEqual({"changelog": "- Heat at 20 \u00b0C.\n", "changelogType": "markdown",
                          "displayName": "Anchor 0.1.0-alpha.1", "gameVersions": [102, 202, 302, 401, 402],
                          "releaseType": "alpha"}, metadata)

    def test_the_build_properties_name_the_versions(self):
        props = release.properties()
        self.assertIn("mod_version", props)
        self.assertIn("minecraft_version", props)
        self.assertIn("curseforge_project_id", props)


if __name__ == "__main__":
    unittest.main()
