"""Helpers for the release workflow.

    release.py check REF_TYPE REF_NAME   works out what a workflow run releases, as GitHub step outputs
    release.py notes VERSION             prints that version's section of CHANGELOG.md
    release.py curseforge [--upload] --notes FILE JAR
                                         uploads the jar to CurseForge, or with no --upload only checks
                                         that the token, the project and the game versions are in order

Only the standard library is used, so the workflow needs nothing installed.
"""

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CURSEFORGE = "https://minecraft.curseforge.com"
USER_AGENT = "anchor-release (https://github.com/osir933/project-anchor-minecraft)"
# The Java version the mod is built for, as anchor-neoforge/build.gradle sets it.
JAVA = "25"


class ReleaseError(Exception):
    """Something that stops a release, with a message that says what to fix."""


def properties(path=ROOT / "gradle.properties"):
    """Reads gradle.properties into a dict."""
    values = {}
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    return values


def release_type(version):
    """Returns CurseForge's release type for a version: alpha, beta or release, from its pre-release label."""
    label = version.split("-", 1)[1].lower() if "-" in version else ""
    if label.startswith("alpha"):
        return "alpha"
    if label.startswith(("beta", "rc", "pre")):
        return "beta"
    return "release"


def check(ref_type, ref_name, version):
    """Decides what a workflow run releases.

    A tag must name the mod's version exactly, as v<version>, and publishes it. Any other run only checks.
    """
    if ref_type == "tag":
        if ref_name != "v" + version:
            raise ReleaseError(f"tag {ref_name} does not match mod_version {version} in gradle.properties; "
                               f"tag v{version} or change mod_version")
        publish = True
    else:
        publish = False
    return {"version": version, "release_type": release_type(version), "publish": str(publish).lower()}


def notes(changelog, version):
    """Returns the body of the changelog section whose heading names the version."""
    lines = changelog.splitlines()
    heading = re.compile(r"^##\s+\[?" + re.escape(version) + r"\]?(\s|$)")
    for start, line in enumerate(lines):
        if heading.match(line):
            body = []
            for following in lines[start + 1:]:
                if following.startswith("## "):
                    break
                body.append(following)
            text = "\n".join(body).strip()
            if text:
                return text + "\n"
            break
    raise ReleaseError(f"CHANGELOG.md has no notes under a '## {version}' heading")


def game_version_ids(version_types, versions, minecraft, loader, java):
    """Picks the CurseForge game version ids a file for this Minecraft version, loader and Java needs.

    Minecraft and the loader must be listed; Java and the client and server environments are added when
    CurseForge lists them.
    """
    slugs = {t["id"]: t.get("slug", "") for t in version_types}

    def matching(test):
        found = []
        for v in sorted(versions, key=lambda v: v["id"]):
            type_id = v.get("gameVersionTypeID", v.get("gameVersionTypeId"))
            if test(slugs.get(type_id, ""), v.get("name", "")):
                found.append(v["id"])
        return found

    game = matching(lambda slug, name: slug.startswith("minecraft") and name == minecraft)
    if not game:
        raise ReleaseError(f"CurseForge does not list Minecraft {minecraft} yet")
    loaders = matching(lambda slug, name: slug == "modloader" and name.lower() == loader.lower())
    if not loaders:
        raise ReleaseError(f"CurseForge does not list the {loader} loader")
    ids = [game[0], loaders[0]]
    ids += matching(lambda slug, name: slug == "java" and name == f"Java {java}")[:1]
    ids += matching(lambda slug, name: slug == "environment" and name in ("Client", "Server"))
    return ids


def multipart(fields, file_field, file_name, file_bytes):
    """Encodes form fields and one file as multipart/form-data; returns the content type and the body."""
    boundary = "anchor-" + uuid.uuid4().hex
    parts = []
    for name, value in fields.items():
        parts.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"\r\n\r\n{value}\r\n"
                     .encode("utf-8"))
    parts.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"{file_field}\"; "
                 f"filename=\"{file_name}\"\r\nContent-Type: application/java-archive\r\n\r\n".encode("utf-8"))
    parts.append(file_bytes)
    parts.append(f"\r\n--{boundary}--\r\n".encode("utf-8"))
    return f"multipart/form-data; boundary={boundary}", b"".join(parts)


def request(path, token, data=None, content_type=None):
    """Calls CurseForge's upload API and returns the decoded JSON answer."""
    headers = {"X-Api-Token": token, "User-Agent": USER_AGENT, "Accept": "application/json"}
    if content_type:
        headers["Content-Type"] = content_type
    req = urllib.request.Request(CURSEFORGE + path, data=data, headers=headers,
                                 method="POST" if data is not None else "GET")
    try:
        with urllib.request.urlopen(req, timeout=120) as response:
            return json.load(response)
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")[:500]
        raise ReleaseError(f"CurseForge answered {e.code} to {path}: {detail}") from None


def curseforge(jar, notes_file, upload, props, token):
    """Uploads a jar to CurseForge, or checks everything an upload needs."""
    project = props.get("curseforge_project_id", "")
    if not token or not project:
        missing = " and ".join(name for name, value in (("the CURSEFORGE_TOKEN secret", token),
                                                        ("curseforge_project_id in gradle.properties", project))
                               if not value)
        print(f"Skipping CurseForge: set {missing} to upload there.")
        return None
    ids = game_version_ids(request("/api/game/version-types", token), request("/api/game/versions", token),
                           props["minecraft_version"], "NeoForge", JAVA)
    version = props["mod_version"]
    metadata = {
        "changelog": Path(notes_file).read_text(encoding="utf-8"),
        "changelogType": "markdown",
        "displayName": f"{props.get('mod_name', 'Anchor')} {version}",
        "gameVersions": ids,
        "releaseType": release_type(version),
    }
    print(f"CurseForge project {project}: {metadata['displayName']} as {metadata['releaseType']}, "
          f"game versions {ids}")
    if not upload:
        print("Checked only; nothing was uploaded.")
        return None
    content_type, body = multipart({"metadata": json.dumps(metadata)}, "file", Path(jar).name,
                                   Path(jar).read_bytes())
    answer = request(f"/api/projects/{project}/upload-file", token, body, content_type)
    print(f"Uploaded to CurseForge as file {answer.get('id')}")
    return answer.get("id")


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    check_command = commands.add_parser("check")
    check_command.add_argument("ref_type")
    check_command.add_argument("ref_name")
    notes_command = commands.add_parser("notes")
    notes_command.add_argument("version")
    curseforge_command = commands.add_parser("curseforge")
    curseforge_command.add_argument("--upload", action="store_true")
    curseforge_command.add_argument("--notes", required=True)
    curseforge_command.add_argument("jar")
    args = parser.parse_args(argv)
    props = properties()
    try:
        if args.command == "check":
            outputs = check(args.ref_type, args.ref_name, props["mod_version"])
            print(", ".join(f"{key}={value}" for key, value in outputs.items()))
            if os.environ.get("GITHUB_OUTPUT"):
                with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as out:
                    out.writelines(f"{key}={value}\n" for key, value in outputs.items())
        elif args.command == "notes":
            sys.stdout.write(notes((ROOT / "CHANGELOG.md").read_text(encoding="utf-8"), args.version))
        else:
            curseforge(args.jar, args.notes, args.upload, props, os.environ.get("CURSEFORGE_TOKEN", ""))
    except ReleaseError as e:
        print(f"::error::{e}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
