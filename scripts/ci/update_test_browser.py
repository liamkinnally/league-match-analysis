"""Refresh the pinned browser archives from Google's Chrome for Testing Stable feed."""
import argparse
import hashlib
import json
import os
import re
import stat
import sys
import tempfile
import time
import unicodedata
import zipfile
from http.client import HTTPException
from pathlib import Path
from urllib.request import HTTPRedirectHandler, Request, build_opener

FEED = "https://googlechromelabs.github.io/chrome-for-testing/last-known-good-versions-with-downloads.json"
STORAGE = "https://storage.googleapis.com/chrome-for-testing-public"
MANIFEST = Path("frontend/test-browser-pins.json")
INVENTORY = {
    "chromeHashes": ("chrome", ("mac-arm64", "mac-x64", "linux-arm64", "linux64", "win32", "win64")),
    "visualShellHashes": ("chrome-headless-shell", ("linux64", "linux-arm64")),
}
CHUNK = 1024 * 1024
MAX_ARCHIVE = 512 * CHUNK
MAX_EXPANDED = 2 * 1024 * CHUNK


class NoRedirects(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def open_url(url):
    request = Request(url, headers={"Accept-Encoding": "identity"})
    return build_opener(NoRedirects()).open(request, timeout=60)


def checked_response(response, url, types):
    if response.status != 200 or response.geturl() != url:
        raise ValueError("Unexpected HTTP status or destination")
    if response.headers.get_content_type() not in types:
        raise ValueError("Unexpected HTTP content type")
    if response.headers.get("Content-Encoding", "identity").lower() != "identity":
        raise ValueError("Unexpected content encoding")


def unique_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON key")
        result[key] = value
    return result


def version_tuple(version):
    if not isinstance(version, str) or not re.fullmatch(r"(?:0|[1-9][0-9]{0,9})(?:\.(?:0|[1-9][0-9]{0,9})){3}", version):
        raise ValueError("Invalid four-part Chrome version")
    return tuple(int(part) for part in version.split("."))


def read_pins(data):
    pins = json.loads(data, object_pairs_hook=unique_keys)
    if not isinstance(pins, dict) or set(pins) != {"browserVersion", *INVENTORY}:
        raise ValueError("Invalid browser manifest fields")
    version_tuple(pins["browserVersion"])
    for field, (_, platforms) in INVENTORY.items():
        hashes = pins[field]
        if not isinstance(hashes, dict) or set(hashes) != set(platforms):
            raise ValueError(f"Invalid platform inventory in {field}")
        if not all(isinstance(value, str) and re.fullmatch("[0-9a-f]{64}", value) for value in hashes.values()):
            raise ValueError(f"Invalid SHA-256 in {field}")
    return pins


def stable_archives():
    with open_url(FEED) as response:
        checked_response(response, FEED, {"application/json"})
        data = response.read(CHUNK + 1)
    if not data or len(data) > CHUNK:
        raise ValueError("Invalid Stable metadata size")
    feed = json.loads(data, object_pairs_hook=unique_keys)
    stable = feed["channels"]["Stable"]
    if stable["channel"] != "Stable":
        raise ValueError("Expected the Stable channel")
    version = stable["version"]
    version_tuple(version)
    archives = {}
    for field, (artifact, platforms) in INVENTORY.items():
        urls = {}
        entries = stable["downloads"][artifact]
        if not isinstance(entries, list):
            raise ValueError(f"Invalid {artifact} download list")
        for entry in entries:
            platform = entry["platform"]
            if platform not in platforms:
                continue
            expected = f"{STORAGE}/{version}/{platform}/{artifact}-{platform}.zip"
            if entry["url"] != expected or platform in urls:
                raise ValueError(f"Unexpected or duplicate {artifact} URL for {platform}")
            urls[platform] = expected
        if set(urls) != set(platforms):
            raise ValueError(f"Incomplete {artifact} downloads")
        archives[field] = urls
    return version, archives


def executable_name(artifact, platform):
    folder = f"{artifact}-{platform}"
    if platform.startswith("mac-"):
        return f"{folder}/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing"
    suffix = ".exe" if platform.startswith("win") else ""
    return f"{folder}/{artifact}{suffix}"


def safe_parts(name):
    # Accept only child components. In particular, a ".." after a symlink cannot
    # be safely simplified before resolving the link.
    parts = name.split("/")
    if (len(name) > 4096 or len(parts) > 128 or "\\" in name or ":" in name
            or any(ord(char) < 32 for char in name)
            or any(not part or part.endswith((".", " ")) for part in parts)):
        raise ValueError("Unsafe archive path")
    return tuple(unicodedata.normalize("NFC", part).casefold() for part in parts)


def verify_members(zipped, folder):
    names = set()
    links = set()
    for item in zipped.infolist():
        name = item.orig_filename.removesuffix("/") if item.is_dir() else item.orig_filename
        parts = safe_parts(name)
        if name.split("/", 1)[0] != folder or parts in names or item.flag_bits & 1:
            raise ValueError("Unexpected ZIP member")
        names.add(parts)
        if stat.S_ISLNK(item.external_attr >> 16):
            if len(parts) == 1 or item.is_dir() or not 0 < item.file_size <= 4096:
                raise ValueError("Unexpected ZIP symlink")
            safe_parts(zipped.read(item).decode("utf-8"))
            links.add(parts)

    # An unzipper must never write another ZIP member through a link, regardless
    # of member order or case/Unicode aliases on the destination filesystem.
    if links and any(path[:i] in links for path in names for i in range(1, len(path))):
        raise ValueError("ZIP member nested under a symlink")


def archive_digest(url, artifact, platform):
    with open_url(url) as response, tempfile.TemporaryFile() as archive:
        checked_response(response, url, {"application/zip", "application/octet-stream", "application/x-zip-compressed"})
        length = int(response.headers.get("Content-Length", "0"))
        if not 0 < length <= MAX_ARCHIVE:
            raise ValueError("Invalid archive length")
        digest = hashlib.sha256()
        size = 0
        deadline = time.monotonic() + 600
        for chunk in iter(lambda: response.read(CHUNK), b""):
            size += len(chunk)
            if size > length or time.monotonic() > deadline:
                raise ValueError("Archive exceeds its size or time limit")
            archive.write(chunk)
            digest.update(chunk)
        if size != length:
            raise ValueError("Truncated archive")
        archive.seek(0)
        if archive.read(4) != b"PK\x03\x04":
            raise ValueError("Expected a ZIP archive")
        with zipfile.ZipFile(archive) as zipped:
            members = zipped.infolist()
            if len(members) > 50000 or sum(item.file_size for item in members) > MAX_EXPANDED:
                raise ValueError("Invalid archive contents size")
            verify_members(zipped, f"{artifact}-{platform}")
            binary = zipped.getinfo(executable_name(artifact, platform))
            if binary.is_dir() or binary.file_size == 0 or stat.S_ISLNK(binary.external_attr >> 16):
                raise ValueError("Expected browser executable in ZIP")
            if zipped.testzip() is not None:
                raise ValueError("Archive failed CRC verification")
    return digest.hexdigest()


def publish(path, original, pins):
    contents = json.dumps(pins, indent=2) + "\n"
    with tempfile.NamedTemporaryFile(dir=path.parent, mode="w", encoding="utf-8", delete=False) as temp:
        temporary_path = Path(temp.name)
    try:
        temporary_path.write_text(contents, encoding="utf-8")
        temporary_path.chmod(stat.S_IMODE(path.stat().st_mode))
        if path.read_bytes() != original:
            raise ValueError("Browser manifest changed during the update")
        os.replace(temporary_path, path)
    finally:
        temporary_path.unlink(missing_ok=True)


def update(path, *, check=False, expected_version=None):
    original = path.read_bytes()
    pins = read_pins(original)
    version, archives = stable_archives()
    if expected_version is not None:
        version_tuple(expected_version)
        if version != expected_version:
            raise ValueError(f"Stable changed since discovery: expected {expected_version}, received {version}")
    if version_tuple(version) <= version_tuple(pins["browserVersion"]):
        return False, pins["browserVersion"]
    if check:
        return True, version
    result = {"browserVersion": version}
    for field, (artifact, platforms) in INVENTORY.items():
        result[field] = {
            platform: archive_digest(archives[field][platform], artifact, platform)
            for platform in platforms
        }
    publish(path, original, result)
    return True, version


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Discover updates without downloading browser archives")
    parser.add_argument("--expected-version", help="Fail if Stable changed since an earlier check")
    args = parser.parse_args(argv)
    try:
        changed, version = update(MANIFEST, check=args.check, expected_version=args.expected_version)
    except (OSError, ValueError, KeyError, TypeError, HTTPException, zipfile.BadZipFile, RuntimeError) as error:
        print(f"Chrome for Testing update failed: {error}", file=sys.stderr)
        return 1
    print(f"changed={str(changed).lower()}")
    print(f"version={version}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
