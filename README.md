# Bookmarks 独立二开工程

## 1. 交付范围

本工程基于 **IntelliJ IDEA 2026.2.4 / 262.11879.100** 的官方公开源码，提供可编辑的 Bookmarks 桌面插件、独立 ZIP 构建、开发沙箱和运行验证。安装包写入 IDEA 的用户插件目录，官方 IDEA 安装目录保持原样。

版本信息固定如下，便于复现和后续跟踪上游。

| 项目 | 值 |
|---|---|
| 上游仓库 | <https://github.com/JetBrains/intellij-community> |
| 上游标签 | `idea/262.11879.100` |
| 上游提交 | `32cd543bd4ee8e36118f392f28d07da04078849d` |
| 插件 ID | `intellij.bookmarks.plugin` |
| 插件名称 | `Bookmarks Manager (Local Source Build)` |
| 插件版本 | `262.11879.100.2` |
| 验证环境 | 官方 IDEA 2026.2.4，macOS Apple Silicon，Java 25 |

**安装采用 IDEA 的用户级插件更新机制。** 沿用官方插件 ID、Action ID 和服务接口，在原有书签状态中增加分组 ID 和父分组 ID，开发版本在用户插件目录中优先加载。IDE 中只有一套 Bookmarks，现有 Java、VCS、调试器和任务上下文可以继续使用相同接口。卸载这个用户级更新并重启后，IDEA 会使用安装目录内原有的内置版本。

## 2. 目录与修改入口

二次开发请修改 `modules/` 和 `plugin/`；`official/` 保留逐文件校验的上游快照。

| 路径 | 用途 |
|---|---|
| `modules/core/src/com/` | 书签管理、分组、行跟踪、菜单动作、工具窗口及旧 API 兼容实现 |
| `modules/core/resources/` | 核心服务、工具窗口和 Action 注册 |
| `modules/debugger/` | 调试断点列表适配 |
| `modules/vcs/` | VCS Log 书签适配 |
| `plugin/plugin.xml` | 插件名称、版本、目标 IDEA 构建号、模块声明 |
| `official/` | 185 个原始文件，含公开 API、原始构建定义、图标、快捷键、适配器、测试及 RPC 相关公开源码 |
| `source-manifest.json` | 上游来源、提交号、每个原始文件的 SHA-256 |
| `tools/` | 构建、IDE 项目生成、开发运行、验证和源码归档脚本 |
| `tests/runtime/` | 在官方 IDEA 进程中运行的插件冒烟检查 |
| `docs/SOURCE_MAP.md` | 业务入口与 SDK 依赖边界 |
| `docs/VALIDATION.md` | 安装和运行验证记录 |
| `.github/workflows/build-plugin.yml` | 可手动运行的 GitHub Actions 构建与产物上传 |
| `docs/CI.md` | 云端构建、下载、配置与复现说明 |

本次 ZIP 包含核心、调试器和 VCS 三个桌面模块。公开 API、Favorites 桥接、Java 包及任务上下文适配由官方 SDK 提供，对应公开源码已保留。`modules/core/src/rpc/` 以及 `official/` 中的 backend/frontend 源码保留供参考，当前桌面构建不包含 Remote Development。官方描述引用的 `intellij.platform.frontend.split.bookmarks` 在该标签的 Community 公开仓库中没有实现源码，因此没有伪造或以反编译代码补齐。

## 3. 准备与打开工程

需要 Python 3、官方 IDEA 2026.2.4 SDK，并启用 IDEA 自带的 Markdown 插件（`org.intellij.plugins.markdown`）。Gradle 命令由随工程提供的 Wrapper 执行，编译使用该官方 SDK 自带的 Kotlin 2.4 和 Java 25 编译器。

配置自己的官方 IDEA 路径。macOS 可指向 `.app` 或 `Contents`，Linux 可指向解压后的 IDEA 目录。

```bash
export BOOKMARKS_IDEA_HOME="/你的路径/IntelliJ IDEA.app"
```

生成本地 IDEA 工程配置后，用 IDEA 2026.2.4 打开工程目录。若 IDE 提示选择 Project SDK，请选择 Java 25；也可以使用官方 IDEA 自带的 `Contents/jbr/Contents/Home`。

```bash
./gradlew setupIdea
```

SDK 的绝对路径仅写入本地 `.idea/` 和根目录 `.iml`，这两类文件不进入源码归档。构建和运行命令可以直接在 IDEA 的 Terminal 中执行。

## 4. 构建与开发运行

构建安装包，产物为 `dist/bookmarks-desktop-262.11879.100.2.zip`。

```bash
./gradlew buildPlugin
```

也可在一次命令中指定 SDK 路径。

```bash
./gradlew buildPlugin -PideaHome="/你的路径/IntelliJ IDEA.app"
```

Python 构建入口与 Gradle 使用同一套逻辑，便于没有 Gradle 环境时直接执行。

```bash
python3 tools/build.py --idea-home "$BOOKMARKS_IDEA_HOME"
```

启动带开发版插件的官方 IDEA；配置、缓存、日志和插件放在 `build/development-sandbox/`，测试项目也位于该目录。

```bash
./gradlew runIde
```

修改代码后重新构建并重启开发实例。发布自己的下一次本地更新前，在 `plugin/plugin.xml` 中递增 `<version>`。当前兼容范围锁定 `262.11879.100`，更换目标 SDK 时需要重新编译和运行验证。

## 5. GitHub Release 与一键构建

已发布版本可直接在 [Releases](https://github.com/codeleep/idea-bookmarks/releases) 中下载；[最新版本](https://github.com/codeleep/idea-bookmarks/releases/latest) 的 Assets 里选择 `bookmarks-desktop-版本号.zip`。

1. 打开仓库的 **Actions → Build IDEA Bookmarks Plugin → Run workflow**。
2. 选择默认分支，保持 `publish_release` 勾选，点击 **Run workflow**。
3. 构建验证通过后自动创建对应版本的 Release，在 Summary 的版本下载链接或 Releases 中下载安装包。
4. 在 IDEA 中执行 **Install Plugin from Disk**，直接选择这个 ZIP，然后重启。

默认分支推送新版本也会自动构建并发布；版本标签为 `v` 加 `plugin/plugin.xml` 中的版本号。已经发布的同版本不会被覆盖，更新功能后需要递增版本号。Release 附件保留在对应版本页面。

插件 ZIP 采用原文件上传，下载后无需解压。`idea-bookmarks-版本号-sources.zip` 是源码包；`bookmarks-build-reports` 包含校验值和本轮运行报告。下载 Actions 产物需要登录 GitHub，默认保留 30 天。

工作流会自动下载并校验固定版本的官方 Linux x64 SDK，使用它自带的编译器，完成原始源码校验、三个插件模块编译、IDE 进程内检查和源码归档。推送到 `main`、`master`、`codex/**` 分支或 `v*` 标签，以及 Pull Request 也会触发构建。详细配置和复现命令见 [CI 使用说明](docs/CI.md)。

## 6. 安装到官方 IDEA

安装步骤使用 IDEA 自带的插件管理器。

1. 确认 `Help → About` 中的版本为 IDEA 2026.2.4，构建号 `262.11879.100`。
2. 打开 `Settings → Plugins → 齿轮 → Install Plugin from Disk`。
3. 选择 `dist/bookmarks-desktop-262.11879.100.2.zip`。
4. 确认本地构建插件的来源提示，应用设置并重启 IDEA。
5. 已安装插件中应显示 `Bookmarks Manager (Local Source Build)`，版本 `262.11879.100.2`。
6. 打开 `View → Tool Windows → Bookmarks`，添加和导航书签。

本机旧版 IDEA 2025.2.1 不在兼容范围内。用户插件目录由 IDEA 管理，安装过程不需要拷贝文件到 `.app/Contents/plugins` 或修改 IDEA 的 JAR。

## 7. Markdown 描述与树形分组

选中行书签或文件书签，点击“编辑 Markdown 描述…”或使用书签的编辑动作。描述会在 **IDEA 自带的 Markdown 编辑页**打开，支持原生语法高亮、格式化、补全和编辑/预览布局切换。编辑内容即时保存到当前分组中的当前书签，可使用 IDEA 的撤销功能修改；关闭编辑页不会丢失已保存描述。

编辑页使用内存中的 Markdown 文档，项目目录不会新增 `.md` 文件。完整原文保存在书签的 `description` 字段，中文、多行、空行和 Markdown 标记均保留。Bookmarks 工具窗口下方提供只读摘要预览，树节点和侧栏提示只显示描述的单行摘要。原生编辑页的渲染、图片和扩展能力遵循内置 Markdown 插件的设置；工具窗口的轻量预览支持常用格式，不自动加载图片。

选中分组后，通过工具栏或右键菜单“新建子分组…”建立层级。右键“移动分组…”可选择新的父分组，也可以拖动分组到另一分组中间；拖到同级边缘调整顺序。分组下可以同时放置子分组和书签，层级不限。

- 同一父分组内名称唯一，不同分支允许同名。
- 移动分组会保留其完整子树；禁止移到自身或后代，禁止产生同级名称冲突。
- 删除非空分组会先确认，并删除该分组的全部子分组和其中的书签；其他分组中的同一文件书签保留。
- 旧列表自动成为顶层分组，原有书签和描述保留。父子关系由稳定 ID 保存，重命名不影响层级。

升级会扩展项目书签状态。若后续卸载或降级，旧版没有树形分组和 Markdown 展示能力；需要保留层级时，应先备份项目书签状态。当前工程支持本地桌面模式。

## 8. 验证与源码归档

检查原始快照并运行实际 IDE 内的冒烟检查。

```bash
./gradlew verifyOriginalSources smokeTest
```

自动检查共 50 项，覆盖插件实际加载、原生 Markdown 文档及编辑器提供器、编辑回写、多层分组、循环拦截、嵌套导航、XML 恢复和旧数据迁移。GUI 检查及安装方式见 `docs/VALIDATION.md`。

生成可分享的完整工程源码包。

```bash
./gradlew sourceZip
```

产物为 `dist/idea-bookmarks-262.11879.100.2-sources.zip`。源码归档包含可编辑模块、官方公开源码快照、Wrapper、工具、测试和文档；官方 IDEA SDK、下载缓存及本机配置不放入归档。

## 9. 来源与许可

原始源码文件的 JetBrains 版权和 Apache 2.0 声明保持原样。工程保留 `NOTICE.txt`、`LICENSE-APACHE-2.0.txt` 和上游 `official/LICENSE.txt`。本地构建产物使用明确的开发版名称和供应商信息。
