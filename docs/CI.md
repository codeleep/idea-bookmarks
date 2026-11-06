# GitHub Actions 验证与版本发布

## 1. 触发规则

**PR 合并不发布 Release；正式分发只允许版本 tag。** 工作流入口为 [Build IDEA Bookmarks Plugin](https://github.com/codeleep/idea-bookmarks/actions/workflows/build-plugin.yml)。

不同事件对应以下行为。

| 事件或引用 | 编译与 IDE 运行检查 | 上传安装包及完整源码 | 创建 Release |
|---|---|---|---|
| `feature/*`、`fixbug/*` 推送 | 是 | 否 | 否 |
| 面向 `main` 的 PR | 是 | 否 | 否 |
| PR 合入 `main` | 是 | 否 | 否 |
| 在分支点击 Run workflow | 是 | 否 | 否 |
| 推送匹配版本、指向已合入提交的 `v*` tag | 是 | 是 | 是 |
| 在同一个有效 tag 上手动重试 | 是 | 是 | 未发布草稿可恢复；已发布附件不覆盖 |
| 错误版本、指向未合入提交的 tag | 前置检查拒绝 | 否 | 否 |

普通 CI 为验证实际安装和加载，会临时生成插件 ZIP；不会生成源码分发包、上传安装包或创建 tag。普通运行保留构建与运行报告，失败时保留诊断日志。

## 2. 发布一个版本

先通过 PR 将功能、版本号和工作流合入 `main`。需要发版时，在已合入提交上创建 tag；工作流不会因为合并而自动创建 tag。

当前插件版本是 `262.11879.100.8`，对应 tag 为 `v262.11879.100.8`。发版命令如下，只在确定需要发布该版本时执行。

```bash
git switch main
git pull --ff-only
git tag -a v262.11879.100.8 -m "Bookmarks 262.11879.100.8"
git push origin v262.11879.100.8
```

tag 必须与 `plugin/plugin.xml` 完全匹配，并解析到本次构建的提交；该提交必须已经合入 `origin/main`。支持轻量和附注 tag。历史已合入提交也可发版，但不会把 tag 移动到后续提交。

前置检查通过后，工作流下载并校验官方 SDK、编译插件、运行真实 IDE 检查、归档源码，最后发布。发布脚本还会通过 GitHub API 再次确认 tag 已存在、提交匹配且已合入 `main`，不允许从分支直接调用脚本隐式创建 tag。

## 3. 下载与恢复失败发布

在 [版本列表](https://github.com/codeleep/idea-bookmarks/releases) 中选择版本，或打开 [最新版本](https://github.com/codeleep/idea-bookmarks/releases/latest)。版本资产如下。

| 资产 | 用途 |
|---|---|
| `bookmarks-desktop-版本号.zip` | 可直接通过 IDEA 从磁盘安装的插件 ZIP |
| `idea-bookmarks-版本号-sources.zip` | 完整二开源码、构建工具、测试和文档 |
| `SHA256SUMS` | 安装包及源码包校验值 |
| `build-report.json` | 编译记录及精确安装包校验值 |
| `runtime-validation.json` | 对该安装包的真实 IDE 运行验证 |

发布先创建草稿，上传全部五个资产并校验大小及 digest，确认后再公开。失败时在同一个 tag 上重跑即可恢复草稿；不得移动 tag，也不覆盖已公开版本。已有无 tag 的旧草稿必须先由维护者创建正确 tag，发布器不会代建。

tag 运行的 Actions 安装包与源码产物采用 `archive: false`，不额外包装 ZIP，默认保留 30 天；报告也保留 30 天。失败诊断保留 7 天。Release 附件不受此期限影响。

## 4. 手动验证与本地复现

Actions 页面选择 **Run workflow**，选择 `main` 或开发分支即可仅验证；没有 `publish_release` 开关，分支手动运行不能发布。选择有效的既有 tag 可重跑该 tag 的完整发布流程。

本地完整构建与源码归档如下，不会发布 GitHub Release。

```bash
python3 tools/ci_build.py --idea-home "/你的路径/IntelliJ IDEA.app"
```

仅做与分支 CI 相同的编译和运行检查时使用下面的命令；不生成本轮源码包及分发校验清单，也不删除目录中的旧产物。

```bash
python3 tools/ci_build.py --idea-home "/你的路径/IntelliJ IDEA.app" --verification-only
```

发布规则的本地验证命令如下；测试使用临时 Git 仓库和模拟 GitHub 响应，不创建远端 tag 或 Release。

```bash
python3 -m unittest discover -s tests -p 'test_*.py' -v
```

## 5. 固定配置

构建与发布入口固定如下，更换 IDEA 基线时应同步维护。

| 项目 | 配置 |
|---|---|
| 工作流 | `.github/workflows/build-plugin.yml` |
| SDK 锁定 | `.github/idea-sdk.json`，官方 IDEA `2026.2.4 / 262.11879.100` |
| 执行环境 | `ubuntu-24.04` / Linux x64，SDK 自带 Kotlin 2.4 与 Java 25 |
| 编译入口 | `tools/ci_build.py`，分支运行传入 `--verification-only` |
| tag 前置检查 | `tools/release_policy.py` |
| 发布入口 | `tools/publish_release.py`，拒绝非匹配 tag 及不存在的 tag |
| 构建超时 | 30 分钟 |
| 权限 | 验证 `contents: read`，独立 tag 发布任务 `contents: write` |
| 密钥 | 使用 `github.token`，无需额外个人访问令牌 |

SDK 仅供构建，不放入安装包或源码包。编译和运行报告只关联本轮安装包；发版时的 `SHA256SUMS` 仅包含本轮安装包与源码包。

## 6. 默认分支与 PR

仓库默认展示 `main`。服务端要求所有修改通过 PR，禁止直接推送、强推和删除，管理员也无绕过权限。开发分支使用 `feature/<名称>` 或 `fixbug/<名称>`；已有旧命名分支保留但不再更新。

PR 必须通过 `Validate branch policy` 和 `Build and verify installable ZIP`，并基于最新 `main` 验证。检查通过后可合并，但合并本身不会发布任何版本。

服务端规则的配置源为 `.github/rulesets/main.json` 和 `.github/rulesets/branch-names.json`，修改配置文件不会自动修改 GitHub 设置。协作及发版约定见根目录 [AGENTS.md](../AGENTS.md)。
