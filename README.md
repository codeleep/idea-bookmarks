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
| 插件版本 | `262.11879.100.8` |
| 验证环境 | 官方 IDEA 2026.2.4，macOS Apple Silicon，Java 25 |

**安装采用 IDEA 的用户级插件更新机制。** 沿用官方插件 ID、Action ID 和服务接口，使用带稳定 ID 的独立书签表、分组树及引用结构，开发版本在用户插件目录中优先加载。IDE 中只有一套 Bookmarks，现有 Java、VCS、调试器和任务上下文可以继续使用相同接口。卸载这个用户级更新并重启后，IDEA 会使用安装目录内原有的内置版本；新格式数据的降级边界见 [存储说明](docs/STORAGE.md)。

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
| `docs/STORAGE.md` | 格式 2、迁移及原版 Git 分支上下文链路 |
| `docs/AI_BOOKMARKS.md` | AI 整理分组、共享描述与指定分支历史的指南 |
| `tools/bookmarks_store.py` | AI 可用的 JSON 导出、校验和带备份写回工具 |

本次 ZIP 包含核心、调试器和 VCS 三个桌面模块。公开 API、Favorites 桥接、Java 包及原分支监听器、上下文归档由官方 SDK 提供，对应公开源码已保留。书签上下文适配器在本插件内升级，用于完整恢复新存储结构。`modules/core/src/rpc/` 以及 `official/` 中的 backend/frontend 源码保留供参考，当前桌面构建不包含 Remote Development。官方描述引用的 `intellij.platform.frontend.split.bookmarks` 在该标签的 Community 公开仓库中没有实现源码，因此没有伪造或以反编译代码补齐。

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

构建安装包，产物为 `dist/bookmarks-desktop-262.11879.100.8.zip`。

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

## 5. GitHub Release 与一键验证

已发布版本可直接在 [Releases](https://github.com/codeleep/idea-bookmarks/releases) 中下载；[最新版本](https://github.com/codeleep/idea-bookmarks/releases/latest) 的 Assets 里选择 `bookmarks-desktop-版本号.zip`。

**只有推送匹配版本的 tag 才会打包分发并发布 Release。** 分支推送、PR 和合入 `main` 只做编译及 IDE 运行验证，不上传正式安装包，也不创建 Release 或自动创建 tag。

1. 在 `feature/<名称>` 或 `fixbug/<名称>` 分支开发，通过 PR 合入 `main`。
2. 需要发版时，确认 `plugin/plugin.xml` 的版本号，在对应的 `main` 已合入提交上创建并推送 `v<版本号>` tag，例如 `v262.11879.100.8`。
3. tag 工作流校验版本、提交及 `main` 归属，构建验证成功后上传安装包、完整源码及报告，创建对应 Release。
4. 在 IDEA 中执行 **Install Plugin from Disk**，直接选择 Release 中的 ZIP，然后重启。

手动验证：打开 **Actions → Build IDEA Bookmarks Plugin → Run workflow**，选择 `main` 或开发分支即可。分支上的手动运行只验证；选择已存在的匹配版本 tag 可重试该版本流程。

已经发布的同版本不会被覆盖；标签也不会移动。插件 ZIP 采用原文件上传，下载后无需解压。`idea-bookmarks-版本号-sources.zip` 是完整源码包，`bookmarks-build-reports` 包含校验和与运行报告。正式安装包和源码 Actions 产物只在 tag 构建中上传，默认保留 30 天，Release 附件保留在对应版本页面。

工作流使用固定 SHA-256 的官方 Linux x64 SDK，仅编译插件。普通 CI 会临时生成插件 ZIP 用于实际安装和运行检查，正式源码分发包与校验清单只在 tag 构建中生成。详细配置见 [CI 使用说明](docs/CI.md)。

默认分支为 `main`，禁止直接推送，管理员也必须通过 PR。新功能使用 `feature/<名称>`，缺陷修复使用 `fixbug/<名称>`；PR 通过分支命名与安装包构建检查后合入。协作约定见 [AGENTS.md](AGENTS.md)。

## 6. 安装到官方 IDEA

安装步骤使用 IDEA 自带的插件管理器。

1. 确认 `Help → About` 中的版本为 IDEA 2026.2.4，构建号 `262.11879.100`。
2. 打开 `Settings → Plugins → 齿轮 → Install Plugin from Disk`。
3. 选择 `dist/bookmarks-desktop-262.11879.100.8.zip`。
4. 确认本地构建插件的来源提示，应用设置并重启 IDEA。
5. 已安装插件中应显示 `Bookmarks Manager (Local Source Build)`，版本 `262.11879.100.8`。
6. 打开 `View → Tool Windows → Bookmarks`，添加和导航书签。

本机旧版 IDEA 2025.2.1 不在兼容范围内。用户插件目录由 IDEA 管理，安装过程不需要拷贝文件到 `.app/Contents/plugins` 或修改 IDEA 的 JAR。

## 7. Markdown 描述与树形分组

选中行书签或文件书签，点击“编辑”或使用书签的编辑动作。描述会在 **IDEA 自带的 Markdown 编辑页**打开，支持原生语法高亮、格式化、补全和编辑/预览布局切换。编辑内容即时保存到各组共享的书签记录，可使用 IDEA 的撤销功能修改；关闭编辑页不会丢失已保存描述。

编辑页使用内存中的 Markdown 文档，项目目录不会新增 `.md` 文件。同一个书签可以同时属于多个组，所有组共享一份 Markdown 描述和同一个编辑缓冲区。在任意组修改会同步更新；移除某个组的引用后，其他组仍可查看和编辑。完整原文保存在书签的 `description` 字段，中文、多行、空行和 Markdown 标记均保留。选中单个真实书签时，Bookmarks 工具窗口下方显示“描述”面板，提供只读摘要预览；选中分组、仅用于聚合的文件节点、多项或清空选择时，面板及其分隔条一起收起，列表使用完整空间。树节点和侧栏提示只显示描述的单行摘要。原生编辑页的渲染、图片和扩展能力遵循内置 Markdown 插件的设置；工具窗口的轻量预览支持常用格式，不自动加载图片。

将鼠标停在代码编辑器左侧的书签图标上，会在悬停提示首行加粗显示书签名称，名称与列表一致，取描述的第一行摘要；Markdown 标题和粗体等标记会去掉。下方保留助记符和快捷键信息，描述正文不整篇弹出。修改任意所属组里的共享描述后，提示同步更新；空描述沿用普通书签提示。

选中书签后，使用工具栏、右键菜单或 Find Action 中的“管理所属分组…”，在树中勾选多个组。父组与子组独立勾选，至少保留一个组；删除书签请使用删除操作。

只有一个组引用时，删除书签会直接完成。多个组引用时，会提示选择“仅从当前组移除”“彻底删除（所有组）”或“取消”。删除分组仍只移除该分组中的引用，其他分组中的同一书签保留。旧数据中同一书签的不同描述会合并保留原文，见 [多组书签说明](docs/MULTI_GROUP.md)。

点击“创建书签列表”右侧的放大镜，打开“搜索书签”。搜索覆盖当前上下文的**完整描述正文（含 Markdown 代码块）、分组完整路径、文件路径和代码锚点**，不受树节点摘要或预览长度限制。输入普通文本即可，忽略大小写；空白分隔的多个词必须同时匹配，可跨字段命中。不会扫描项目中未设书签的源码。

结果显示文件及行号、命中内容片段和全部所属分组；共享书签只出现一次。双击结果或按回车，会选中树中的书签并打开对应代码位置；查询命中某个分组时优先定位该分组中的引用。搜索遵循原有 Git 分支/全局上下文设置，不合并其他分支存档。尚未加载对应 provider 的记录保留可搜索性，但不允许定位。搜索本身不修改书签数据。

选中分组后，通过工具栏或右键菜单“新建子分组…”建立层级。右键“移动分组…”可选择新的父分组，也可以拖动分组到另一分组中间；拖到同级边缘调整顺序。分组下可以同时放置子分组和书签，层级不限。

- 同一父分组内名称唯一，不同分支允许同名。
- 移动分组会保留其完整子树；禁止移到自身或后代，禁止产生同级名称冲突。
- 删除非空分组会先确认，并删除该分组的全部子分组和其中的书签；其他分组中的同一书签保留。
- 旧列表自动成为顶层分组，原有书签和描述保留。父子关系由稳定 ID 保存，重命名不影响层级。

升级会扩展项目书签状态。若后续卸载或降级，旧版没有树形分组和 Markdown 展示能力；需要保留层级时，应先备份项目书签状态。当前工程支持本地桌面模式。

## 8. 存储结构、Git 分支与 AI 整理

从版本 `.4` 开始，当前状态保存在项目 `.idea/bookmarks.xml`，使用格式 2：书签及 Markdown 描述只保存一次，分组通过 `bookmarkIds` 引用。旧工作区状态与旧分支快照可迁移，首次旧状态保留备份。

Git 分支继续沿用官方 `BranchContextTracker → WorkingContextManager` 的保存与恢复链路及原有开关；本插件升级书签适配器以保留树形层级、稳定 ID 和多组引用。未保存过上下文的新分支保留原版行为，不自动清空当前书签。

AI 行书签的 `line` 从 0 开始；`lineText` 可省略，提供时必须保留源码行的缩进、Tab 和尾空白，不能使用 `strip/lstrip/rstrip`。离线 `validate` 与 `apply` 会校验本地文件、行号和精确锚点，错误在备份及写回前拒绝。

[存储结构说明](docs/STORAGE.md) 列出了格式、位置、迁移、设置和降级边界。[AI 整理指南](docs/AI_BOOKMARKS.md) 提供当前项目及指定 Git 分支历史的导出、编辑、校验、写回和验收步骤。工具要求目标项目关闭，校验源文件版本并自动备份，避免覆盖其他分支的上下文。

## 9. 导入、导出与分享

在 Bookmarks 工具栏或右键菜单打开“导入、导出与分享”。可以导出当前上下文全部书签，或选中分组及其完整子树。

- **JSON**：保留分组树、多组引用、共享 Markdown、快捷标记和稳定 ID；项目内路径自动转为项目相对路径，便于换目录或分享给同一项目的其他检出。
- **Markdown**：导出分组路径、代码位置、相对代码链接和原始描述，作为代码阅读文档分享；建议保存到项目根目录。
- **导入 JSON**：默认合并，可选择替换；先查看变更明细及 IDEA 原生差异对比，再确认导入。描述冲突可选择保留现有或采用导入内容。
- **恢复**：导入前自动备份到 `.idea/bookmarks-import-backups/`，备份失败则不写入；需要恢复时重新导入备份并选择替换。

导入只操作当前书签上下文，不批量改写其他 Git 分支已保存的快照；预览期间数据改变会拒绝写入。详细规则和限制见 [导入导出说明](docs/IMPORT_EXPORT.md)。

## 10. 验证与源码归档

检查原始快照并运行实际 IDE 内的冒烟检查。

```bash
./gradlew verifyOriginalSources smokeTest
```

自动检查共 233 项，覆盖插件实际加载、原生 Markdown 文档及编辑器提供器、编辑回写、多层分组、循环拦截、嵌套导航、XML 恢复和旧数据迁移，以及多组引用、共享描述、删除范围和代码行位置变化。新版增加格式 2、原版分支保存/恢复、严格校验、未知 provider 保留和 AI 写入格式与 IDE 读取的联通检查。导入导出新增真实导航、可移植 JSON 与离线工具互通、合并冲突、备份失败拦截、旧编辑缓冲区隔离和预览过期保护检查。精确锚点新增缩进、Tab、尾空白、中文、严格失效节点及原版保护检查，另在已发布 `.6` 上通过 31 项兼容检查并完成真实界面跳转，记录见 `docs/evidence/exact-anchor-validation.json`。全文搜索增加完整正文、代码块、长行尾部、中文、组合匹配、共享去重、Unicode 摘要偏移及工具栏注册检查；实际界面搜索和跳转记录见 `docs/evidence/gui-search-validation.json`。书签图标提示新增名称、中文及特殊字符、共享更新、空描述和只读性检查。历史 GUI 检查见 `docs/VALIDATION.md`，当前验证见 `docs/evidence/runtime-validation.json`。

生成可分享的完整工程源码包。

```bash
./gradlew sourceZip
```

产物为 `dist/idea-bookmarks-262.11879.100.8-sources.zip`。源码归档包含可编辑模块、官方公开源码快照、Wrapper、工具、测试和文档；官方 IDEA SDK、下载缓存及本机配置不放入归档。

## 11. 来源与许可

原始源码文件的 JetBrains 版权和 Apache 2.0 声明保持原样。工程保留 `NOTICE.txt`、`LICENSE-APACHE-2.0.txt` 和上游 `official/LICENSE.txt`。本地构建产物使用明确的开发版名称和供应商信息。
