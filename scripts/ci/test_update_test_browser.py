"""Offline checks for the scheduled Chrome for Testing updater."""
import hashlib
import io
import json
import stat
import tempfile
import unittest
import zipfile
from contextlib import redirect_stderr, redirect_stdout
from email.message import Message
from pathlib import Path
from unittest.mock import patch

import update_test_browser as updater


class Response(io.BytesIO):
    status = 200

    def __init__(self, url, body, mime):
        super().__init__(body)
        self.url = url
        self.headers = Message()
        self.headers["Content-Type"] = mime
        self.headers["Content-Length"] = str(len(body))

    def geturl(self):
        return self.url


def make_zip(member, content):
    archive = io.BytesIO()
    with zipfile.ZipFile(archive, "w") as zipped:
        zipped.writestr(member, content)
    return archive.getvalue()


class UpdateTest(unittest.TestCase):
    current = "157.0.8103.9"
    latest = "157.0.8103.10"

    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.path = Path(temp.name) / "test-browser-pins.json"
        self.pins = {"browserVersion": self.current}
        for field, (_, platforms) in updater.INVENTORY.items():
            self.pins[field] = dict.fromkeys(platforms, "a" * 64)
        self.original = json.dumps(self.pins).encode()
        self.path.write_bytes(self.original)
        self.urls = []
        self.archives = {}
        self.make_release(self.latest)

    def make_release(self, version):
        downloads = {}
        self.stable = {"version": version, "channel": "Stable", "downloads": downloads}
        for artifact, platforms in updater.INVENTORY.values():
            downloads[artifact] = []
            for platform in platforms:
                folder = f"{artifact}-{platform}"
                url = f"{updater.STORAGE}/{version}/{platform}/{folder}.zip"
                downloads[artifact].append({"platform": platform, "url": url})
                name = updater.executable_name(artifact, platform)
                self.archives[url] = make_zip(name, f"fixture {folder}".encode())

    def respond(self, url):
        self.urls.append(url)
        if url == updater.FEED:
            feed = {"channels": {"Stable": self.stable}}
            return Response(url, json.dumps(feed).encode(), "application/json")
        return Response(url, self.archives[url], "application/zip")

    def run_update(self, opener=None):
        with patch.object(updater, "open_url", side_effect=opener or self.respond):
            return updater.update(self.path)

    def assert_atomic(self):
        self.assertEqual(self.path.read_bytes(), self.original)
        self.assertEqual(list(self.path.parent.iterdir()), [self.path])

    def test_numeric_advances_and_major_changes_hash_all_eight_archives(self):
        for new in [self.latest, "158.0.1.0"]:
            with self.subTest(new=new):
                self.path.write_bytes(self.original)
                self.path.chmod(0o644)
                self.make_release(new)
                self.urls.clear()
                self.assertEqual(self.run_update(), (True, new))
                saved = json.loads(self.path.read_bytes())
                self.assertEqual(saved["browserVersion"], new)
                self.assertEqual(len(set(self.urls)), 9)
                self.assertEqual(len(self.urls), 9)
                for field, (artifact, platforms) in updater.INVENTORY.items():
                    entries = self.stable["downloads"][artifact]
                    self.assertEqual(set(saved[field]), set(platforms))
                    for entry in entries:
                        digest = hashlib.sha256(self.archives[entry["url"]]).hexdigest()
                        self.assertEqual(saved[field][entry["platform"]], digest)
                self.assertEqual(self.path.stat().st_mode & 0o777, 0o644)

    def test_same_or_older_release_skips_every_archive(self):
        for version in [self.current, "157.0.8103.8", "156.99.9999.99"]:
            self.make_release(version)
            self.urls.clear()
            self.assertEqual(self.run_update(), (False, self.current))
            self.assertEqual(self.urls, [updater.FEED])
            self.assert_atomic()

    def test_partial_duplicate_or_substituted_url_fails_before_download(self):
        for defect in ["missing", "duplicate", "origin", "scheme", "different_release", "query", "wrong_channel"]:
            with self.subTest(defect=defect):
                self.make_release(self.latest)
                rows = self.stable["downloads"]["chrome-headless-shell"]
                if defect == "missing":
                    rows.pop()
                elif defect == "duplicate":
                    rows.append(dict(rows[0]))
                elif defect == "wrong_channel":
                    self.stable["channel"] = "Beta"
                else:
                    url = rows[0]["url"]
                    rows[0]["url"] = {
                        "origin": url.replace("storage.googleapis.com", "storage.googleapis.com.example.org"),
                        "scheme": url.replace("https:", "http:"),
                        "different_release": url.replace(self.latest, self.current),
                        "query": url + "?generation=other",
                    }[defect]
                self.urls.clear()
                with self.assertRaises(ValueError):
                    self.run_update()
                self.assertEqual(self.urls, [updater.FEED])
                self.assert_atomic()

    def test_corrupt_local_manifest_refuses_network(self):
        del self.pins["chromeHashes"]["linux64"]
        self.path.write_text(json.dumps(self.pins))
        with self.assertRaises(ValueError):
            self.run_update()
        self.assertEqual(self.urls, [])

    def test_corrupt_late_archive_leaves_old_manifest_and_emits_no_output(self):
        entry = self.stable["downloads"]["chrome-headless-shell"][-1]
        url = entry["url"]
        self.archives[url] = self.archives[url].replace(b"fixture chrome-", b"changed chrome-", 1)
        stdout, stderr = io.StringIO(), io.StringIO()
        with (patch.object(updater, "MANIFEST", self.path),
              patch.object(updater, "open_url", side_effect=self.respond),
              redirect_stdout(stdout), redirect_stderr(stderr)):
            self.assertEqual(updater.main([]), 1)
        self.assertEqual(self.urls[-1], url)
        self.assertEqual(len(self.urls), 9)
        self.assertEqual(stdout.getvalue(), "")
        self.assertIn("CRC", stderr.getvalue())
        self.assert_atomic()

    def mac_links_archive(self, entries, files=()):
        folder = "chrome-mac-arm64"
        artifact = io.BytesIO()
        with zipfile.ZipFile(artifact, "w") as zipped:
            zipped.writestr(updater.executable_name("chrome", "mac-arm64"), b"Chrome")
            for path in files:
                zipped.writestr(f"{folder}/{path}", b"resource")
            for name, target in entries:
                info = zipfile.ZipInfo(f"{folder}/{name}" if name else folder)
                info.create_system = 3
                info.external_attr = (stat.S_IFLNK | 0o777) << 16
                zipped.writestr(info, target)
        return artifact.getvalue()

    def test_supports_chained_framework_links(self):
        base = "Google Chrome for Testing.app/Contents/Frameworks/Example.framework"
        archive = self.mac_links_archive([
            (f"{base}/Resources", "Versions/Current/Resources"),
            (f"{base}/Versions/Current", self.latest),
        ], [f"{base}/Versions/{self.latest}/Resources/icon"])
        url = self.stable["downloads"]["chrome"][0]["url"]
        self.archives[url] = archive
        self.run_update()
        pins = json.loads(self.path.read_bytes())
        self.assertEqual(pins["chromeHashes"]["mac-arm64"], hashlib.sha256(archive).hexdigest())

    def test_symlinks_cannot_escape_or_redirect_member_extraction(self):
        cases = [
            ([("escape", "../../outside")], []),
            ([("escape", "/absolute/outside")], []),
            ([("safe-looking", "sub/bridge/file"), ("sub/bridge", "../../../outside")], []),
            # Both targets normalize inside the root if processed separately. Following
            # bridge first makes the two parent steps escape the archive instead.
            ([("nested/bridge", "../shallow"), ("nested/escape", "bridge/../../outside")], []),
            ([("escape", r"C:\outside")], []),
            ([("", "sibling-of-browser")], []),
            ([("link", "inside")], ["LINK/nested"]),
        ]
        url = self.stable["downloads"]["chrome"][0]["url"]
        for entries, files in cases:
            with self.subTest(links=entries), self.assertRaises(ValueError):
                self.archives[url] = self.mac_links_archive(entries, files)
                self.run_update()
            self.assert_atomic()

    def test_response_validation_and_interrupted_stream(self):
        for defect in ["html", "redirect", "length", "timeout", "not_zip", "missing_executable"]:
            def response(url):
                reply = self.respond(url)
                if url == updater.FEED:
                    return reply
                if defect == "html":
                    reply.headers.replace_header("Content-Type", "text/html")
                elif defect == "redirect":
                    reply.url = "https://example.org/file.zip"
                elif defect == "length":
                    reply.headers.replace_header("Content-Length", str(len(reply.getvalue()) + 1))
                elif defect == "timeout":
                    reply.close()
                    raise TimeoutError("fixture read timeout")
                else:
                    reply.close()
                    data = b"not a zip" if defect == "not_zip" else make_zip("chrome-mac-arm64/README", b"fixture")
                    reply = Response(url, data, "application/zip")
                return reply

            with self.subTest(defect=defect), self.assertRaises((ValueError, KeyError, TimeoutError)):
                self.run_update(response)
            self.assert_atomic()

    def test_cli_noop_outputs_only_ci_values(self):
        self.make_release(self.current)
        stdout = io.StringIO()
        with (patch.object(updater, "MANIFEST", self.path),
              patch.object(updater, "open_url", side_effect=self.respond), redirect_stdout(stdout)):
            self.assertEqual(updater.main([]), 0)
        self.assertEqual(stdout.getvalue(), f"changed=false\nversion={self.current}\n")

    def test_cli_check_proposes_new_version_without_modifying_pins(self):
        stdout = io.StringIO()
        with (patch.object(updater, "MANIFEST", self.path),
              patch.object(updater, "open_url", side_effect=self.respond), redirect_stdout(stdout)):
            self.assertEqual(updater.main(["--check"]), 0)
        self.assertEqual(stdout.getvalue(), f"changed=true\nversion={self.latest}\n")
        self.assertEqual(self.urls, [updater.FEED])
        self.assert_atomic()

    def test_stable_change_between_discovery_and_update_fails_before_archives(self):
        with patch.object(updater, "open_url", side_effect=self.respond):
            with self.assertRaisesRegex(ValueError, "Stable changed since discovery"):
                updater.update(self.path, expected_version=self.current)
            self.assertEqual(self.urls, [updater.FEED])
            self.assert_atomic()
            self.assertEqual(updater.update(self.path, expected_version=self.latest), (True, self.latest))

    def test_concurrent_manifest_change_is_preserved(self):
        changed = self.original + b"\n"

        def response(url):
            if url != updater.FEED:
                self.path.write_bytes(changed)
            return self.respond(url)

        with self.assertRaisesRegex(ValueError, "changed during"):
            self.run_update(response)
        self.assertEqual(self.path.read_bytes(), changed)
        self.assertEqual(list(self.path.parent.iterdir()), [self.path])


if __name__ == "__main__":
    unittest.main()
