# Bookmarks Markdown 与树形分组验证记录

这份记录是版本 `.2` 的历史记录。版本 `.3` 的多组引用、共享描述和删除规则见 [多组书签说明](MULTI_GROUP.md) 及 `evidence/gui-multigroup-validation.json`。版本 `.4` 的新存储、AI 工具及分支链路见 [存储说明](STORAGE.md) 和当前 `evidence/runtime-validation.json`。

## 1. 结论与环境

**新版 `262.11879.100.2` 在未修改的官方 IDEA 2026.2.4 中加载运行，Markdown 描述和树形分组验证通过。** 描述编辑直接使用 IDEA 内置 Markdown 插件的编辑器和实时预览，修改即时回写书签状态。

| 项目 | 值 |
|---|---|
| 验证日期 | 2026-11-04 |
| IDE | JetBrains 官方 IntelliJ IDEA 2026.2.4，`IU-262.11879.100` |
| 系统 | macOS，Apple Silicon / aarch64 |
| 编译与运行 JDK | SDK 自带 JBR 25 |
| 插件 | `intellij.bookmarks.plugin` / `262.11879.100.2` |
| 编译范围 | 核心 90 个源文件，调试器 1 个，VCS 1 个 |
| 编译输出 | 268 个 `.class` 文件 |
| 原始源码完整性 | 185 个原始文件与固定提交的 SHA-256 清单一致 |
| 自动化检查 | 官方 IDE 进程内 50 项通过 |

## 2. 安装方式与验证对象

基线版本 `.1` 已经通过官方 GUI 的 `Settings → Plugins → Install Plugin from Disk` 安装、重启加载和书签基本操作检查，原记录保留在 `docs/evidence/gui-baseline-262.11879.100.1.json`。

新版 `.2` 的自动化和 GUI 检查将交付 ZIP 解压至隔离的**用户插件目录**，由官方 IDEA 在启动时加载。新版未再次完成 GUI 磁盘安装对话框的操作验证。安装布局、插件 ID 和用户级覆盖机制与基线保持一致，交付 ZIP 中的 4 个 JAR 与最终 GUI 环境中的文件逐字节一致。

官方 SDK 的 `.app` 目录始终未替换文件。最终 `codesign --verify --deep --strict` 通过，签名有效。所有测试配置、项目、缓存和插件均位于工程 `build/` 下。

## 3. 自动化检查

自动化检查使用实际安装包，在官方 IDEA 进程中调用插件服务、状态序列化、实际工具窗口节点和内置 Markdown 文件编辑器提供器。

| 范围 | 检查内容 | 结果 |
|---|---|---|
| 源码归档独立构建 | 解压源码包到干净目录后编译，4 个 JAR 的每一项类与资源都与交付插件一致 | 通过 |
| 实际插件加载 | 开发版本、用户目录、服务实现资源来自交付 ZIP | 通过 |
| 原功能 | 文件与行书签、助记符、默认分组、重命名、删除、状态恢复 | 通过 |
| 多层分组 | 三级结构、父子关系、前序顺序、稳定 ID、跨分支同名 | 通过 |
| 移动边界 | 拦截自身、循环和同级重名，移动后保留后代 | 通过 |
| 删除子树 | 删除后代，其他分组中的同一文件书签保留 | 通过 |
| 原生 Markdown 接入 | 内存文件使用 Markdown 类型，官方 split editor provider 接受该文档，重复编辑复用文档 | 通过 |
| 描述回写 | 原生 Document 修改保存到正确嵌套书签，恢复完整 Markdown | 通过 |
| 只读预览 | 标题、粗体、代码、表格和链接，过滤活动 HTML 与自动图片加载 | 通过 |
| 嵌套 UI 节点与定位 | RootNode / GroupNode 层级、深层分组与书签选择、行书签弹窗保留祖先 | 通过 |
| 持久化与迁移 | XML 往返恢复层级、默认组与多行描述，旧平面列表迁为顶层分组 | 通过 |
| 损坏状态处理 | 孤立父分组、自引用和循环关系修复，分组保留 | 通过 |

## 4. 真实界面检查

以下为最终安装包在官方桌面界面中的观测。

| 操作 | 观测结果 |
|---|---|
| 重启加载开发版插件 | 启动日志记录版本 `.2`，JAR 与交付 ZIP 完全一致 |
| 展开三级分组 | 恢复父子结构，深层分组内显示原有行书签 |
| 新建子分组 | 动作打开指定父分组的创建窗口，输入“验收分组”后显示在该父分组下 |
| 移动分组 | 树形目标选择器打开，确认后“验收分组”移到上一层 |
| 深层书签导航 | 选择行书签后，编辑器光标从 `6:1` 到 `1:1` |
| 编辑描述 | 打开内置 Markdown 编辑页，原文中的中文、空行和标记完整恢复 |
| 原生编辑与预览 | 编辑区语法高亮，JCEF 预览显示标题、粗体、列表、Java 代码块、表格和链接 |
| 即时回写与撤销 | 追加“重启后编辑验证。”立即出现在工具窗口预览；原生撤销后同步恢复 |
| 退出保存 | 磁盘状态保留稳定分组 ID、parentId 和完整多行 Markdown |
| 项目文件 | 项目目录没有生成描述 `.md` 文件 |
| 最终运行日志 | 本轮启动到退出没有新增 SEVERE 事件 |

界面验证曾发现普通 Swing 按钮触发时需要显式获取文档读锁，已经修复并重新构建；最终 GUI 会话和自动化检查使用的是修复后的 ZIP。原版 SDK 的两条服务覆盖 WARN 及插件市场网络告警未作为功能通过依据。

## 5. 复现命令与证据

指定完整、未修改的官方 SDK，并启用自带 Markdown 插件。

```bash
export BOOKMARKS_IDEA_HOME="/你的路径/IntelliJ IDEA.app"
./gradlew verifyOriginalSources smokeTest
./gradlew runIde
```

在开发实例中建立多层分组、编辑 Markdown 描述，正常退出后再运行同一实例检查恢复。源码归档命令如下。

```bash
./gradlew sourceZip
```

| 证据 | 内容 |
|---|---|
| `docs/evidence/source-build-validation.json` | 从源码归档独立编译及逐项产物对比 |
| `docs/evidence/runtime-validation-262.11879.100.2.json` | 交付 ZIP 的 SHA-256、版本及 50 项检查 |
| `docs/evidence/runtime-checks-262.11879.100.2.txt` | 自动化 PASS 项 |
| `docs/evidence/gui-validation.json` | 最终 GUI 观测、精确安装方式、恢复路径和安装 JAR 校验 |
| `docs/evidence/gui-baseline-262.11879.100.1.json` | 基线版本从磁盘安装的历史记录 |
| `docs/evidence/sdk-signature.txt` | 官方 SDK 签名校验结果 |
| `build/compile.log`、`build/runtime-sandbox/process.log` | 本机完整构建和自动化日志，不进入源码归档 |
| `build/gui-sandbox/log/idea.log` | 本机 GUI 日志，不进入源码归档 |

## 6. 验证边界

当前验收范围为官方 IDEA 2026.2.4 本地桌面模式。Remote Development、其他 IDEA 版本、Windows/Linux 界面、全部官方平台回归测试，以及卸载回退未纳入本次验收。调试器与 VCS 模块已编译和加载，未逐项验证其全部交互。

内置 Markdown 插件为必需依赖，不能禁用。完整原生编辑页遵循 IDEA 的 Markdown 设置和已启用扩展；工具窗口中的轻量只读预览与原生 JCEF 预览的渲染范围不同。旧版不支持分组父子关系，降级前需要备份书签状态。
