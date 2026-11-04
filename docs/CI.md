# GitHub Actions 构建与安装说明

## 1. 一键构建

构建入口为 [Build IDEA Bookmarks Plugin](https://github.com/codeleep/idea-bookmarks/actions/workflows/build-plugin.yml)。

1. 登录 GitHub，进入仓库的 Actions 页面。
2. 选择 **Build IDEA Bookmarks Plugin**，点击 **Run workflow**。
3. 选择默认分支，保持 `publish_release` 勾选并确认运行；取消勾选可仅构建。
4. 等待工作流显示成功，打开本次运行的 Summary。
5. 构建与 Release 任务都成功后，点击版本下载链接；也可到 [Releases](https://github.com/codeleep/idea-bookmarks/releases) 选择对应版本。

GitHub 要求带 `workflow_dispatch` 的工作流存在于默认分支，页面才显示手动运行入口。[官方说明](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow)

## 2. 自动发布与版本下载

默认分支构建验证通过后，自动按 `plugin/plugin.xml` 中的版本创建 Release，例如 `v262.11879.100.4`。匹配插件版本的 `v*` 标签构建也会发布。Pull Request 和其他分支只构建，手动运行可取消 `publish_release` 以跳过发布。

在 [版本列表](https://github.com/codeleep/idea-bookmarks/releases) 中选择版本，或打开 [最新版本](https://github.com/codeleep/idea-bookmarks/releases/latest)。每个 Release 的 Assets 包含可安装 ZIP、完整源码 ZIP、`SHA256SUMS`、构建报告和 IDE 内运行报告。这些附件保留在对应 Release 中，不受 Actions 产物 30 天保留期影响。

发布先创建草稿并上传附件，确认上传大小与 GitHub 返回的校验值后再公开。重复构建已发布版本时保留原附件；发布新代码请递增版本号。已有版本标签不会移动，失败重试可更新尚未创建 Git 标签的未公开草稿到本轮已验证提交。通过 GitHub API 创建草稿时直接使用响应中的 Release ID，避免新建后按标签或列表查询的同步延迟；随后使用 GitHub CLI 的 [上传](https://cli.github.com/manual/gh_release_upload) 和 [公开草稿](https://cli.github.com/manual/gh_release_edit) 命令。

每次成功构建也保留以下 Actions 产物，默认保留 30 天。下载 Actions 产物需要登录 GitHub。

| 产物 | 用途 |
|---|---|
| `bookmarks-desktop-版本号.zip` | IDEA 可安装插件包，直接从磁盘安装 |
| `idea-bookmarks-版本号-sources.zip` | 完整二开源码，包括 Actions 配置与构建脚本 |
| `bookmarks-build-reports` | `SHA256SUMS`、构建报告、本轮 IDE 内检查报告 |
| `bookmarks-build-diagnostics` | 仅在失败时上传的编译与 IDE 日志，保留 7 天 |

安装步骤：官方 IDEA 2026.2.4 → Settings → Plugins → 齿轮 → Install Plugin from Disk，选择 `bookmarks-desktop-版本号.zip`，然后重启。内置 Markdown 插件需要保持启用。

安装包和源码包使用 `actions/upload-artifact` 的 `archive: false`，保留原 ZIP 文件，不额外嵌套一层 ZIP。[官方上传参数](https://github.com/actions/upload-artifact/blob/v7.0.2/action.yml)

## 3. 固定配置

当前配置如下，更换 IDEA 基线时需要同时维护 SDK 锁定文件和插件兼容范围。

| 项目 | 配置 |
|---|---|
| 工作流文件 | `.github/workflows/build-plugin.yml` |
| SDK 锁定文件 | `.github/idea-sdk.json` |
| 执行环境 | GitHub 托管的 `ubuntu-24.04` / Linux x64 |
| SDK 版本 / 构建号 | `2026.2.4` / `262.11879.100` |
| SDK 来源 | `https://download.jetbrains.com/idea/idea-2026.2.4.tar.gz` |
| SDK 校验 | 锁定官方归档的 SHA-256，下载后校验，匹配后解压 |
| 编译器 | SDK 自带 Kotlin 2.4 与 Java 25 |
| 编译入口 | `python3 tools/ci_build.py` |
| 发布入口 | `python3 tools/publish_release.py` |
| 版本规则 | 标签 `v` + 插件版本；同版本已发布则保留原附件 |
| 工作流超时 | 30 分钟 |
| 触发方式 | 手动、指定分支 Push、`v*` 标签 Push、Pull Request |
| 工作流权限 | 构建任务 `contents: read`；独立发布任务 `contents: write` |
| 仓库密钥 | 无需自定义 Secrets 或个人访问令牌 |

SDK 只用于本轮构建，不打进安装包或源码包。第一次运行需要下载完整 SDK。产物只在编译及 IDE 内检查全部成功后上传；失败时可下载 diagnostics 排查。

## 4. 本地复现

使用已安装的官方 IDEA SDK 执行相同构建、检查和打包步骤。

```bash
python3 tools/ci_build.py --idea-home "/你的路径/IntelliJ IDEA.app"
```

在 Linux x64 且具有 Python 3.12、curl 和 headless Java 所需字体库的环境中，可直接执行以下命令，由脚本下载锁定的 SDK。

```bash
python3 tools/ci_build.py
```

产物位于 `dist/`，本轮检查报告位于 `build/`。脚本在重复使用的工作目录中只为本轮插件包和源码包生成 `SHA256SUMS`，避免将旧版本混入校验清单。

## 5. 维护约定

修改插件功能后，在 `plugin/plugin.xml` 中递增版本并推送到默认分支。工作流会从描述文件读取版本、命名 ZIP 并自动创建对应 Release。手动推送版本标签时，标签必须与描述文件匹配；例如当前版本使用 `v262.11879.100.4`。

升级 SDK 时，更新 `.github/idea-sdk.json` 中的版本、构建号、下载地址和官方 SHA-256，并同步 `plugin/plugin.xml` 的兼容范围及构建参数。脚本会检查锁定构建号与描述文件的一致性，避免使用错误 SDK 生成安装包。

发布规则的本地检查命令如下。

```bash
python3 -m unittest discover -s tests -p 'test_*.py' -v
```

在 Linux 上运行 IDE 内检查时，从 SDK 的 `product-info.json` 读取匹配系统与 CPU 架构的启动配置及 VM options 路径，兼容 Linux 的 `idea64.vmoptions` 和 macOS 的 `idea.vmoptions`。
