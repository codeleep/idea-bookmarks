#!/usr/bin/env python3
"""Publish verified CI artifacts once per plugin version, preserving released assets."""
import argparse
from io import BytesIO
import json
import os
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET
import zipfile

from build import PLUGIN_VERSION, ROOT
from ci_build import sha256
from release_policy import require_version_tag


def gh(*args):
    return subprocess.check_output(["gh", *args], text=True).strip()


def api(path, optional=False, payload=None):
    command = ["gh", "api", path]
    if payload is not None:
        command.extend(["--method", "POST", "--input", "-"])
    result = subprocess.run(command, text=True, capture_output=True,
                            input=json.dumps(payload) if payload is not None else None)
    value = json.loads(result.stdout) if result.stdout.strip() else {}
    if result.returncode:
        if optional and str(value.get("status")) == "404":
            return None
        raise RuntimeError(result.stderr.strip() or f"GitHub API failed: {path}")
    return value


def find_release(repo, tag):
    release = api(f"repos/{repo}/releases/tags/{tag}", optional=True)
    if release:
        return release
    # GitHub's by-tag endpoint excludes drafts whose Git tag is not created yet.
    page = 1
    while True:
        releases = api(f"repos/{repo}/releases?per_page=100&page={page}")
        for candidate in releases:
            if candidate["tag_name"] == tag:
                return candidate
        if len(releases) < 100:
            return None
        page += 1


def validate_assets(directory, version):
    plugin = directory / f"bookmarks-desktop-{version}.zip"
    sources = directory / f"idea-bookmarks-{version}-sources.zip"
    reports = directory / "reports"
    selected = []
    for name in ("SHA256SUMS", "build-report.json", "runtime-validation.json"):
        matches = list(reports.rglob(name))
        if len(matches) != 1:
            raise ValueError(f"Expected exactly one {name} in downloaded reports")
        selected.append(matches[0])
    checksums, build_file, runtime_file = selected
    expected = dict(line.split(maxsplit=1)[::-1] for line in checksums.read_text().splitlines() if line.strip())
    for path in (plugin, sources):
        if expected.get(path.name) != sha256(path):
            raise ValueError(f"Release checksum mismatch: {path.name}")
    with zipfile.ZipFile(plugin) as archive:
        if not archive.namelist() or any(not name.startswith("platform-bookmarks-plugin/lib/") or not name.endswith(".jar") for name in archive.namelist()):
            raise ValueError("Plugin must be a directly installable ZIP, without another wrapper ZIP")
        with zipfile.ZipFile(BytesIO(archive.read("platform-bookmarks-plugin/lib/platform-bookmarks-plugin.jar"))) as jar:
            if ET.fromstring(jar.read("META-INF/plugin.xml")).findtext("version") != version:
                raise ValueError("Plugin descriptor version does not match release version")
    with zipfile.ZipFile(sources) as archive:
        if ET.fromstring(archive.read("idea-bookmarks/plugin/plugin.xml")).findtext("version") != version:
            raise ValueError("Source archive version does not match release version")
    build = json.loads(build_file.read_text())
    runtime = json.loads(runtime_file.read_text())
    if not (build.get("runtimeValidated") and build.get("sha256") == sha256(plugin)
            and runtime.get("result") == "PASS" and runtime.get("checks")
            and runtime.get("pluginVersion") == version and runtime.get("archiveSha256") == sha256(plugin)):
        raise ValueError("Release requires successful runtime verification of the exact plugin ZIP")
    return [plugin, sources, *selected], runtime


def publish(repo, target, ref, assets, notes, version):
    tag = require_version_tag(ref, version)
    tagged = api(f"repos/{repo}/git/ref/tags/{tag}", optional=True)
    if not tagged:
        raise ValueError(f"Version tag {tag} must already exist; create and push it before publishing")
    if api(f"repos/{repo}/commits/{tag}")["sha"] != target:
        raise ValueError(f"Existing tag {tag} points to another commit; tags will not be moved")
    if api(f"repos/{repo}/compare/{target}...main")["status"] not in {"ahead", "identical"}:
        raise ValueError("Version tag must point to a commit already merged into main")
    release = find_release(repo, tag)
    required = {path.name for path in assets}
    if release and not release["draft"]:
        if not required.issubset({asset["name"] for asset in release["assets"]}):
            raise ValueError(f"Existing published release {tag} is incomplete; it will not be overwritten")
        print(f"RELEASE_EXISTS: {release['html_url']}; increment plugin.xml version to release new code")
        return release
    if not release:
        # Use the creation response: draft tag/list queries can lag behind writes.
        release = api(f"repos/{repo}/releases", payload={
            "tag_name": tag, "target_commitish": target, "draft": True,
            "name": f"Bookmarks {version}", "body": notes.read_text(),
        })
        if not release or not release.get("id") or not release.get("draft"):
            raise ValueError("GitHub did not return a newly created draft release")
    else:
        gh("release", "edit", tag, "--repo", repo, "--notes-file", str(notes),
           "--title", f"Bookmarks {version}")
    endpoint = f"repos/{repo}/releases/{release['id']}"
    # Only unpublished drafts can be resumed or replaced on a retry.
    gh("release", "upload", tag, *map(str, assets), "--repo", repo, "--clobber")
    uploaded = api(endpoint)
    for path in assets:
        asset = next((item for item in uploaded["assets"] if item["name"] == path.name), None)
        if not asset or asset["size"] != path.stat().st_size:
            raise ValueError(f"Release asset upload is incomplete: {path.name}")
        if asset.get("digest") and asset["digest"] != f"sha256:{sha256(path)}":
            raise ValueError(f"Uploaded release asset digest mismatch: {path.name}")
    gh("release", "edit", tag, "--repo", repo, "--draft=false")
    return api(endpoint)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifacts-dir", type=Path, default=ROOT / "release-assets")
    parser.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY"))
    parser.add_argument("--target", default=os.environ.get("GITHUB_SHA"))
    parser.add_argument("--ref", default=os.environ.get("GITHUB_REF", ""))
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    try:
        require_version_tag(args.ref, PLUGIN_VERSION)
    except ValueError as error:
        parser.error(str(error))
    assets, runtime = validate_assets(args.artifacts_dir, PLUGIN_VERSION)
    if args.dry_run:
        print(f"RELEASE_DRY_RUN_PASS: v{PLUGIN_VERSION}; {len(assets)} assets; {len(runtime['checks'])} runtime checks")
        return
    if not args.repo or not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", args.repo):
        parser.error("--repo owner/name is required")
    if not args.target or not re.fullmatch(r"[0-9a-f]{40}", args.target):
        parser.error("--target must be the full verified build commit SHA")
    run_id = os.environ.get("GITHUB_RUN_ID")
    run_link = f"https://github.com/{args.repo}/actions/runs/{run_id}" if run_id else ""
    notes = args.artifacts_dir / "release-notes.md"
    notes.write_text(
        f"面向官方 IntelliJ IDEA **{runtime['sdkVersion']} / {runtime['sdkBuild']}** 的 Bookmarks 桌面插件。\n\n"
        f"下载 **{assets[0].name}**，无需解压，在 IDEA 的 **Settings → Plugins → 齿轮 → Install Plugin from Disk** 中选择该文件并重启。请保持内置 Markdown 插件启用。\n\n"
        f"- 支持多层树形分组、同一书签多组引用、共享 Markdown 描述、删除范围，以及格式 2 独立书签表与 ID 引用。\n"
        f"- 复用官方 Git 分支上下文，完整恢复分组树与共享描述；源码内含 AI 整理指南和导出、校验、写回工具。\n"
        f"- 支持 JSON 完整导入导出、Markdown 分享、合并与替换、原生差异预览及导入前自动备份。\n"
        f"- `{assets[1].name}` 是完整二开源码。\n"
        f"- `SHA256SUMS` 用于核对安装包与源码包；JSON 附件是本次构建与运行检查报告。\n"
        f"- IDE 进程内检查：**{len(runtime['checks'])} 项通过**。\n"
        f"- 源码提交：`{args.target}`。\n"
        + (f"- [本次构建记录]({run_link})。\n" if run_link else "")
    )
    release = publish(args.repo, args.target, args.ref, assets, notes, PLUGIN_VERSION)
    if release["draft"]:
        raise RuntimeError("Release is still a draft")
    print(f"RELEASE_PUBLISHED: {release['html_url']}")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        plugin = next(asset for asset in release["assets"] if asset["name"] == assets[0].name)
        with Path(summary).open("a") as stream:
            stream.write(f"## 版本下载\n\n[Release {release['tag_name']}]({release['html_url']}) · [下载可安装 ZIP]({plugin['browser_download_url']})\n\n已有版本会保留原有附件；发布新代码请递增 plugin.xml 版本号。\n")


if __name__ == "__main__":
    main()
