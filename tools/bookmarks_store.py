#!/usr/bin/env python3
"""Export, validate and apply normalized Bookmarks data, including original branch archives."""
import argparse
from copy import deepcopy
import hashlib
from io import BytesIO
import json
import os
from pathlib import Path
import subprocess
import tempfile
import uuid
import xml.etree.ElementTree as ET
import zipfile

VERSION = 2
TYPES = {"DEFAULT", *(f"LETTER_{c}" for c in "ABCDEFGHIJKLMNOPQRSTUVWXYZ"), *(f"DIGIT_{n}" for n in range(10))}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, f"Duplicate JSON key: {key}")
        result[key] = value
    return result


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_object)


def validate(model):
    require(isinstance(model, dict) and set(model) <= {"formatVersion", "bookmarks", "groups", "_source"}, "Unknown document fields")
    require(type(model.get("formatVersion")) is int and model["formatVersion"] == VERSION, "formatVersion must be 2")
    records, groups = model.get("bookmarks"), model.get("groups")
    require(isinstance(records, list) and isinstance(groups, list), "bookmarks and groups must be arrays")
    ids, locations, mnemonics = set(), set(), set()
    for record in records:
        require(isinstance(record, dict) and set(record) == {"id", "provider", "attributes", "type", "descriptionMarkdown"}, "Invalid bookmark fields")
        ident = record["id"]
        require(isinstance(ident, str) and ident.strip() and ident not in ids, "Bookmark IDs must be nonempty and unique")
        ids.add(ident)
        require(isinstance(record["provider"], str) and record["provider"].strip(), "Bookmark provider is required")
        attributes = record["attributes"]
        require(isinstance(attributes, dict) and all(isinstance(k, str) and isinstance(v, str) for k, v in attributes.items()), "attributes must map strings to strings")
        location = record["provider"], tuple(sorted((k, v) for k, v in attributes.items() if k != "lineText"))
        require(location not in locations, "Duplicate bookmark location; reference the existing ID")
        locations.add(location)
        if "line" in attributes:
            try:
                line = int(attributes["line"])
            except ValueError:
                raise ValueError("line must be an integer string") from None
            require(-1 <= line <= 2147483647, "line must be zero-based, or -1 for a file")
        kind = record["type"]
        require(isinstance(kind, str) and kind in TYPES, "Unknown bookmark mnemonic")
        require(kind == "DEFAULT" or kind not in mnemonics, "Duplicate mnemonic")
        mnemonics.add(kind)
        require(record["descriptionMarkdown"] is None or isinstance(record["descriptionMarkdown"], str), "descriptionMarkdown must be a string or null")
    by_id, siblings, used = {}, set(), set()
    for group in groups:
        require(isinstance(group, dict) and set(group) == {"id", "parentId", "name", "isDefault", "bookmarkIds"}, "Invalid group fields")
        ident, parent = group["id"], group["parentId"]
        require(isinstance(ident, str) and ident.strip() and ident not in by_id, "Group IDs must be nonempty and unique")
        require(parent is None or isinstance(parent, str), "parentId must be a string or null")
        require(isinstance(group["name"], str) and group["name"].strip(), "Group name is required")
        require(type(group["isDefault"]) is bool, "isDefault must be boolean")
        sibling = parent, group["name"]
        require(sibling not in siblings, "Duplicate sibling group name")
        siblings.add(sibling)
        refs = group["bookmarkIds"]
        require(isinstance(refs, list) and all(isinstance(ref, str) for ref in refs), "bookmarkIds must be a string array")
        require(len(set(refs)) == len(refs) and set(refs) <= ids, "Duplicate or dangling bookmark reference")
        used.update(refs)
        by_id[ident] = group
    require(sum(group["isDefault"] for group in groups) <= 1, "Only one default group is allowed")
    require(used == ids, "Bookmarks without group references are not allowed")
    for group in groups:
        visited, parent = {group["id"]}, group["parentId"]
        while parent is not None:
            require(parent in by_id and parent not in visited, "Missing parent or group cycle")
            visited.add(parent)
            parent = by_id[parent]["parentId"]
    return model


def option(node, name, default=None):
    found = node.find(f"./option[@name='{name}']")
    return default if found is None else found.get("value", default)


def collection(node, name):
    return node.find(f"./option[@name='{name}']")


def elements(node, name, tag):
    parent = collection(node, name)
    return [] if parent is None else parent.findall(tag)


def parse_state(node):
    require(node is not None, "BookmarksManager / ManagerState not found")
    require(option(node, "formatVersion") == "2", "Upgrade and save with plugin .4 or newer before using this tool")
    require(all(child.tag == "option" and child.get("name") in {"formatVersion", "bookmarks", "groups"} for child in node), "Unknown stored state fields; use a matching tool version")
    records = []
    for saved in elements(node, "bookmarks", "BookmarkState"):
        require(all(child.tag == "attributes" or (child.tag == "option" and child.get("name") in {"id", "provider", "type", "description"}) for child in saved), "Unknown stored bookmark fields")
        records.append({"id": option(saved, "id"), "provider": option(saved, "provider"),
                        "type": option(saved, "type", "DEFAULT"), "descriptionMarkdown": option(saved, "description"),
                        "attributes": {entry.get("key"): entry.get("value") for entry in saved.findall("./attributes/entry")}})
    groups = []
    for saved in elements(node, "groups", "GroupState"):
        require(all(child.tag == "option" and child.get("name") in {"id", "parentId", "name", "default", "isDefault", "bookmarkIds", "bookmarks"} for child in saved), "Unknown stored group fields")
        require(not elements(saved, "bookmarks", "BookmarkState"), "Version 2 groups cannot contain bookmark copies")
        groups.append({"id": option(saved, "id"), "parentId": option(saved, "parentId"),
                       "name": option(saved, "name", ""), "isDefault": option(saved, "default", option(saved, "isDefault", "false")) == "true",
                       "bookmarkIds": [entry.get("value") for entry in elements(saved, "bookmarkIds", "option")]})
    return validate({"formatVersion": VERSION, "bookmarks": records, "groups": groups})


def write_state(model, tag):
    validate(model)
    node = ET.Element(tag)
    def put(parent, name, value):
        if value is not None:
            ET.SubElement(parent, "option", name=name, value=str(value).lower() if type(value) is bool else str(value))
    put(node, "formatVersion", VERSION)
    records = ET.SubElement(node, "option", name="bookmarks")
    for record in model["bookmarks"]:
        saved = ET.SubElement(records, "BookmarkState")
        put(saved, "id", record["id"])
        put(saved, "provider", record["provider"])
        put(saved, "type", record["type"])
        put(saved, "description", record["descriptionMarkdown"])
        attributes = ET.SubElement(saved, "attributes")
        for key, value in record["attributes"].items():
            ET.SubElement(attributes, "entry", key=key, value=value)
    groups = ET.SubElement(node, "option", name="groups")
    for group in model["groups"]:
        saved = ET.SubElement(groups, "GroupState")
        for key in ("id", "parentId", "name"):
            put(saved, key, group[key])
        put(saved, "default", group["isDefault"])
        refs = ET.SubElement(saved, "option", name="bookmarkIds")
        for ident in group["bookmarkIds"]:
            ET.SubElement(refs, "option", value=ident)
    return node


def git_state(project):
    def git(*args):
        result = subprocess.run(["git", "-C", str(project), *args], capture_output=True, text=True)
        return result.stdout.strip() if result.returncode == 0 else None
    return {"branch": git("symbolic-ref", "--quiet", "--short", "HEAD"), "commit": git("rev-parse", "--verify", "HEAD")}


def source_path(project, archive=None):
    return archive.resolve() if archive else project.resolve()/".idea/bookmarks.xml"


def load_source(project, archive=None, branch=None):
    path = source_path(project, archive)
    raw = path.read_bytes()
    if archive:
        require(bool(branch), "--branch is required for an archive")
        with zipfile.ZipFile(BytesIO(raw)) as package:
            entries = [name for name in package.namelist() if name.lstrip("/") == "__branch_context_"+branch]
            require(len(entries) == 1, "Branch context is missing or duplicated")
            xml = package.read(entries[0])
        root = ET.fromstring(xml)
        node = root.find("./bookmarks/ManagerState")
        meta = {"kind": "branch", "path": str(path), "project": str(project.resolve()), "entry": entries[0], "branch": branch}
    else:
        root = ET.fromstring(raw)
        node = root.find("./component[@name='BookmarksManager']")
        meta = {"kind": "project", "path": str(path), "project": str(project.resolve()), **git_state(project)}
    model = parse_state(node)
    model["_source"] = {**meta, "sha256": digest(raw)}
    return model, root, raw


def encode_xml(root):
    ET.indent(root, space="  ")
    return ET.tostring(root, encoding="utf-8", xml_declaration=True)+b"\n"


def apply_model(project, model, archive=None, branch=None):
    validate(model)
    meta = model.get("_source", {})
    path = source_path(project, archive)
    require(meta.get("path") == str(path) and meta.get("project") == str(project.resolve()), "Source path/project mismatch; export this target again")
    require(meta.get("kind") == ("branch" if archive else "project"), "Source kind mismatch")
    require(not archive or meta.get("branch") == branch, "Branch context mismatch")
    lock = path.with_name(path.name+".ai.lock")
    handle = os.open(lock, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        os.close(handle)
        current, root, raw = load_source(project, archive, branch)
        require(meta.get("sha256") == digest(raw), "Source changed after export; export again and merge changes")
        if not archive:
            actual = git_state(project)
            require(all(meta.get(k) == actual[k] for k in ("branch", "commit")), "Git branch/commit changed after export")
            old = root.find("./component[@name='BookmarksManager']")
            saved = write_state(model, "component")
            saved.set("name", "BookmarksManager")
            root.insert(list(root).index(old), saved)
            root.remove(old)
            updated = encode_xml(root)
        else:
            # Archives serialize URLs without the project's macro expansion layer.
            model = deepcopy(model)
            for record in model["bookmarks"]:
                url = record["attributes"].get("url", "")
                marker = "file://$PROJECT_DIR$"
                if url == marker or url.startswith(marker+"/"):
                    record["attributes"]["url"] = "file://"+project.resolve().as_posix()+url[len(marker):]
            owner = root.find("./bookmarks")
            old = owner.find("ManagerState")
            owner.insert(list(owner).index(old), write_state(model, "ManagerState"))
            owner.remove(old)
            buffer = BytesIO()
            with zipfile.ZipFile(BytesIO(raw)) as original, zipfile.ZipFile(buffer, "w") as output:
                output.comment = original.comment
                for info in original.infolist():
                    output.writestr(info, encode_xml(root) if info.filename == meta["entry"] else original.read(info))
            updated = buffer.getvalue()
        backup_dir = path.parent/"bookmarks-backups"
        backup_dir.mkdir(exist_ok=True)
        backup = backup_dir/(path.name+"."+digest(raw)+".bak")
        if not backup.exists():
            backup.write_bytes(raw)
        require(path.read_bytes() == raw, "Source changed during update")
        descriptor, staging = tempfile.mkstemp(prefix=path.name+".", dir=path.parent)
        try:
            with os.fdopen(descriptor, "wb") as stream:
                stream.write(updated)
                stream.flush()
                os.fsync(stream.fileno())
            os.chmod(staging, path.stat().st_mode & 0o777)
            os.replace(staging, path)
        finally:
            if os.path.exists(staging):
                os.unlink(staging)
        return {"path": str(path), "sha256": digest(updated), "backup": str(backup)}
    finally:
        lock.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for command in ("export", "apply"):
        child = commands.add_parser(command)
        child.add_argument("--project", type=Path, required=True)
        child.add_argument("--archive", type=Path)
        child.add_argument("--branch")
        if command == "export":
            child.add_argument("--out", type=Path, required=True)
        else:
            child.add_argument("--input", type=Path, required=True)
            child.add_argument("--project-closed", action="store_true", required=True, help="Confirm the target project / IDE is closed before changing stored state")
    child = commands.add_parser("validate")
    child.add_argument("--input", type=Path, required=True)
    child = commands.add_parser("branches")
    child.add_argument("--archive", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == "export":
            model, _, _ = load_source(args.project, args.archive, args.branch)
            args.out.write_text(json.dumps(model, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
            print(f"EXPORTED: {args.out}; {len(model['bookmarks'])} bookmarks; {len(model['groups'])} groups")
        elif args.command == "validate":
            model = validate(read_json(args.input))
            print(f"VALID: {len(model['bookmarks'])} bookmarks; {len(model['groups'])} groups")
        elif args.command == "apply":
            print(json.dumps(apply_model(args.project, read_json(args.input), args.archive, args.branch), ensure_ascii=False, indent=2))
        else:
            with zipfile.ZipFile(args.archive) as package:
                names = [name.lstrip("/")[len("__branch_context_"):] for name in package.namelist() if name.lstrip("/").startswith("__branch_context_")]
                print(json.dumps(names, ensure_ascii=False, indent=2))
    except (ValueError, OSError, ET.ParseError, zipfile.BadZipFile) as error:
        parser.exit(1, f"ERROR: {error}\n")


if __name__ == "__main__":
    main()
