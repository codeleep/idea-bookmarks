# Bookmarks 源码入口

## 1. 常用修改位置

下面列出独立桌面模块的主要业务入口。

| 需求 | 文件 |
|---|---|
| 书签管理、列表、默认分组、助记符分配 | `modules/core/src/com/intellij/ide/bookmark/BookmarksManagerImpl.kt` |
| 树形分组创建、迁移、移动、循环检测 | `modules/core/src/com/intellij/ide/bookmark/BookmarksManagerImpl.kt` |
| 保存结构、分组和书签状态 | `modules/core/src/com/intellij/ide/bookmark/state.kt` |
| 行编辑、文件变化、书签位置跟踪 | `modules/core/src/com/intellij/ide/bookmark/providers/LineBookmarkProvider.kt` |
| 文件和行书签对象 | `modules/core/src/com/intellij/ide/bookmark/providers/` |
| 编辑器侧栏书签绘制与刷新 | `GutterLineBookmarkRenderer.kt`、`GutterLineBookmarkUpdater.kt`，位于核心 `bookmark` 包 |
| 添加、删除、分组和导航动作 | `modules/core/src/com/intellij/ide/bookmark/actions/` |
| 原生 Markdown 编辑页、内存文档和描述回写 | `modules/core/src/com/intellij/ide/bookmark/ui/MarkdownDescriptionEditor.kt` |
| 描述的只读预览和树节点摘要 | `MarkdownDescriptionPanel.kt`、`MarkdownPane.kt`、`MarkdownDescriptions.kt` |
| 子分组与分组移动动作 | `modules/core/src/com/intellij/ide/bookmark/actions/GroupHierarchyActions.kt` |
| 父分组选择器 | `modules/core/src/com/intellij/ide/bookmark/ui/GroupParentDialog.kt` |
| 任意深度的树与节点定位 | `ui/tree/RootNode.kt`、`ui/tree/GroupNode.kt`、`ui/GroupBookmarkVisitor.kt` |
| 工具窗口、弹窗和树节点 | `modules/core/src/com/intellij/ide/bookmark/ui/` |
| 服务、工具窗口、Action 注册 | `modules/core/resources/intellij.platform.bookmarks.xml` |
| 调试断点列表 | `modules/debugger/src/com/intellij/platform/debugger/impl/bookmarks/BreakpointListProvider.kt` |
| VCS Log 适配 | `modules/vcs/src/com/intellij/platform/vcs/log/impl/bookmarks/VcsLogBookmarkProvider.kt` |
| 插件元信息和兼容范围 | `plugin/plugin.xml` |

## 2. 原始源码与 SDK 合同

以下源码随工程提供，其中平台 API 和桥接在运行时由官方 IDEA 提供。

| 原始路径 | 作用 | 本次构建处理 |
|---|---|---|
| `official/platform/lang-api/src/com/intellij/ide/bookmark/` | Bookmark、BookmarksManager、BookmarkGroup 等接口 | 使用目标 SDK 的接口类，源码用于阅读 |
| `official/platform/lang-api/resources/messages/BookmarkBundle.properties` | 本地化文本 | 使用目标 SDK 资源 |
| `official/platform/favoritesTreeView/` | Favorites URL 桥接 | 使用目标 SDK |
| `official/java/java-bookmarks/` | Java 包书签 | 使用目标 SDK 的 Java 插件模块 |
| `official/platform/tasks-platform-impl/tasks.impl.bookmarks/` | 任务与分支上下文 | 使用目标 SDK 的任务模块 |
| `official/platform/platform-tests/testSrc/com/intellij/ide/` | 官方书签回归测试 | 保留原始测试，未把整个平台测试框架抽成此工程的运行依赖 |
| `official/platform/bookmarks/backend/`、`frontend/` | 远程开发公开实现 | 保留，桌面 ZIP 不编译 |
| `official/platform/platform-resources/src/keymaps/` | 默认键位绑定 | 官方 IDEA 已注册，沿用相同 Action ID |

当前插件沿用平台公开 API，因而可以与官方 IDEA 的 Java、调试器、VCS 和任务模块衔接。新增功能优先在插件自己的实现和资源中扩展；若要改变平台已有 API 的方法签名，需要另行设计兼容接口。

## 3. Markdown 与分组的依赖

原生 Markdown 编辑通过平台公开的 `FileEditorManager.openFile()` 打开 `LightVirtualFile`，文件类型直接引用官方 Markdown 插件的 `MarkdownFileType`。编辑器、实时预览、语言能力和生命周期由 IDEA 的文件编辑器提供器管理，插件不复制 Markdown 编辑器实现。描述文档监听器即时回写 `BookmarkGroup`；关闭页签移除监听器，删除书签或分组关闭对应编辑页。

`plugin/plugin.xml` 和核心模块声明对 `org.intellij.plugins.markdown` 的依赖。构建 classpath 只引用官方 `plugins/markdown/lib/intellij.markdown.jar`，不将其装进交付 ZIP。工具窗口中的只读预览使用 SDK 的 Markdown 解析库和 Jsoup。

树形分组只扩展实现层和序列化状态，平台 `BookmarkGroup` 接口保持兼容。`getGroups()` 为旧调用者提供前序扁平视图；插件自己的树与分组选择器使用稳定 ID、父子关系和完整显示路径，避免不同分支同名时选错组。

## 4. 构建数据流

本地构建只编译插件模块，并把产物放入标准插件 ZIP。

```mermaid
flowchart LR
    Source[modules 中的二开源码] --> Compiler[官方 SDK 内置编译器]
    Sdk[官方 IDEA SDK<br/>只读依赖] --> Compiler
    Compiler --> Jars[插件模块 JAR]
    Meta[plugin.xml 与模块资源] --> Zip[标准插件 ZIP]
    Jars --> Zip
    Zip --> UserPlugins[官方 IDEA 用户插件目录]
```
