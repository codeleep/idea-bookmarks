# AI 书签整理指南

## 1. 任务约定

本指南以插件 `262.11879.100.10` 的格式 3 为准，并兼容读取格式 2。AI 应先阅读 [存储格式](STORAGE.md)，再通过 `tools/bookmarks_store.py` 导出 JSON、整理、校验并写回。JSON 是工具实际支持的编辑格式，IDE 与分支上下文最终仍使用新结构的 XML。

持续更新的指南以仓库 `main` 为准；旧 `codex/bookmarks-standalone` 分支仅保留历史。维护格式 2 的旧项目时，工具保留导出版本；添加关系必须先升级到 `.10` 并采用格式 3。格式 3 必须使用当前工具，不能交给仅支持格式 2 的旧脚本写回。插件的严格锚点保护保持原样。

`.9` 新增 IDE 内的巡检与确认修复，可在不关闭项目的情况下通过插件界面修复当前上下文；本指南的离线写回仍须关闭目标项目。阅读路线、复核基线与保存视图由 IDE 个人状态维护，不进入书签 JSON。编辑分支归档时，只替换目标 `ManagerState`，保留同级的 `BookmarkReadingState` 及其他元素；`BookmarkReadingState` 内的路线、复核记录和去重源码快照也必须原样保留，不因为整理描述而重置基线。详见 [书签工作台、巡检与保存视图](WORKFLOWS.md)。

开始整理前记录以下信息。

| 信息 | 用途 |
|---|---|
| 目标项目或 worktree 的绝对路径 | 避免操作同名工程或另一工作树 |
| 目标 Git 分支、提交号、整理目的 | 确认分析的是哪一版代码 |
| 操作当前项目文件，还是指定分支历史 | 明确写入范围 |
| 现有分组 ID、书签 ID、描述和归属 | 尽量复用已有数据 |
| 新增、改名、移动、增加引用、移除引用、彻底删除的清单 | 给出可审阅的具体变化 |

整理书签不需要创建代码分支、提交、合并或推送 Git。只有任务包含这些操作时才执行。已有未提交代码不属于书签整理范围。

## 2. 选择正确的分支范围

当前项目文件是 IDE 最近保存的当前书签状态，Git 分支历史由原版上下文归档管理。按以下方式选择入口。

| 目标 | 操作方式 |
|---|---|
| 整理正在使用的分支 | 先在 IDEA 中切到目标分支并确认书签已恢复，保存并关闭目标项目，再编辑 `.idea/bookmarks.xml` |
| 整理已经保存的某个分支快照 | 关闭 IDEA，指定 `.contexts.zip` 和精确分支名导出、写回该归档项 |
| 查看其他分支源码作为整理依据 | 使用 `git show 分支:路径` 等只读命令；无需切换当前工作树 |
| 新分支没有历史上下文 | 按原机制先在 IDEA 中建立、保存该分支的书签上下文；工具不凭空创建分支历史 |

直接在终端切换 Git 分支不等于当前书签文件已经恢复到该分支。当前文件模式会校验导出时的 Git 分支和提交号，但无法代替 IDEA 的上下文恢复。写回前确认目标项目已关闭；归档模式应关闭 IDEA，防止另一项目同时写同一归档。

两个原有设置都影响分支恢复：`tasks.enable.bookmark.context.on.branch.switch` 控制书签是否参加上下文；`VcsConfiguration.RELOAD_CONTEXT` 控制分支切换后的上下文恢复。保留用户已有设置，不把开关关闭误判为数据丢失。

## 3. 树形分组与书签身份

按用户查找代码的目的组织树形分组，不把完整代码目录逐层复制成分组。优先复用现有主题，常见分类可以是“业务链路 / 阶段”“模块 / 关键入口”“问题 / 证据与修复”。没有书签内容时不批量创建空目录。

结构变更遵守以下规则。

| 操作 | 数据变更 |
|---|---|
| 新建分组 | 新 UUID、名称、父组 ID；顶层 `parentId` 为 `null` |
| 重命名分组 | 只修改 `name`，保留 `id` |
| 移动分组子树 | 修改根组的 `parentId`，后代和引用不变 |
| 调整顺序 | 调整分组数组中同级项的顺序或组内 `bookmarkIds` 顺序 |
| 一个书签加入多个组 | 在目标组引用已有书签 ID，保留原有归属 |
| 更新代码定位 | 修改同一书签的 `attributes`，保留书签 ID |

不同父组下可以重名，同级不可重名。定位分组使用 ID 和完整父子路径，不能只按名称查找。移动到自身、后代或制造同级重名都应在计划阶段排除。

定位书签时同时核对文件、符号、代码文本和行号。行号从 0 开始；文件书签通常省略行号。普通文件与行书签使用 `com.intellij.ide.bookmark.providers.LineBookmarkProvider`，但 Java 包、VCS 等其他 provider 的属性必须保留，不按普通文件格式强行改写。

### 3.1. 行号与源码锚点

生成和核验行书签时必须遵守以下规则。

| 字段或操作 | 约定 |
|---|---|
| `attributes.line` | 从 **0** 开始的整数字符串；IDEA 中的第 73 行写为 `"72"` |
| `attributes.lineText` | 可省略；提供时必须是该源码行的完整原文，不包含 `LF`、`CRLF` 或 `CR` 换行符 |
| 行首空格与 Tab | 原样保留，不能把 Tab 转为空格或去除缩进 |
| 行尾空格与 Tab | 原样保留，不能为了“清理格式”去除 |
| 中文与其他字符 | 按源文件编码解码并原样保留，不替换、不做 Unicode 归一化 |
| `.strip()`、`.lstrip()`、`.rstrip()` | **禁止用于生成 `lineText`，也禁止用于比较锚点是否匹配** |
| 文件 URL | 复用导出的 IDEA VFS URL 和 `$PROJECT_DIR$` 宏；不要自行将中文、空格编码成 `%xx` |
| 省略 `lineText` | 仍须核对文件、行号、符号和代码；不能删除错误锚点来绕过校验 |

例如源码行是 `"    public static void main(String[] args) {"`，锚点也必须保留前面的四个空格。`"public static void main(String[] args) {"` 虽然去除空白后相同，仍然是错误锚点。

在本仓库根目录运行 Python 时，可以使用工具的源码分行函数。它仅处理 BOM 和换行符，保留缩进、尾空白以及最后的空行。

```python
from pathlib import Path
from tools.bookmarks_store import source_lines

path = Path("/项目绝对路径/src/Example.java")
line = 72  # IDEA 第 73 行
lines = source_lines(path.read_bytes())
assert 0 <= line < len(lines)
attributes = {
    "url": "file://$PROJECT_DIR$/src/Example.java",
    "line": str(line),
    "lineText": lines[line],
}
assert attributes["lineText"] == lines[line]  # 精确比较，不清理空白
```

不要直接用 `splitlines()` 模拟 IDEA：它还会把 Unicode 行分隔字符、换页符等拆成新行。工具支持 UTF-8，以及带 BOM 的 UTF-8/UTF-16/UTF-32；无法按这些编码读取时会报错，不能用忽略解码错误或替换字符的方式通过验收，需先确认 IDEA 实际使用的文件编码。

### 3.2. 置灰与跳转的判定

插件 `LineBookmarkProvider.createValidBookmark()` 会把提供的 `lineText` 与 `Util.readLineText()` 得到的源码原文逐字比较。锚点不一致会创建 `InvalidBookmark`；`InvalidNode.kt` 中的 `UrlNode` 将整行置灰，且书签不能导航。这是防止跳到错误位置的保护，不应改为忽略空白或自动修正行号。

需要修复定位时，回到目标分支源码重新确认文件、符号和 0 起始行号，更新同一书签的定位字段；保留 ID、Markdown、分组和全部共享引用。不能重建整批书签，不能修改业务源码来迎合错误锚点。

相同位置复用一条书签记录。多个组引用同一 ID，共享同一份 Markdown。分支历史是独立快照，修改一个分支的描述不会自动修改其他分支归档中的描述。

## 4. Markdown 描述

首行写可以独立阅读的简短摘要，后面给出用途、源码依据和核验结果。保留用户已有的有效内容，补充事实与推断的区别。

可使用以下模板；其中的路径、符号、分支和提交必须来自目标代码。

```markdown
读取入口：把源端记录转换为下游事件。

## 作用
说明这段代码负责的阶段，以及为什么需要从这里阅读。

## 源码依据
- 文件：`src/main/java/example/Reader.java`
- 符号：`Reader.poll`
- 分支：`feature/example`
- 核验提交：`实际提交号`

## 关键关系
说明调用方、被调用方、状态变化或错误处理位置。

## 核验情况
写清已由源码证实的结论；待验证的推断单独标注。
```

在 JSON 中修改 `descriptionMarkdown`，由 JSON 编码器处理换行和引号。不要直接拼接 XML 转义字符，不把同一描述复制到各组，不为每个组创建一条相同位置的书签。

### 4.1. 关系与内部链接

新增书签仍复用稳定 ID；关联保存在格式 3 的顶层 `relations`，与分组引用分开管理。现有关系默认全部保留，不根据组名推断并重建关系。

| 字段／约束 | AI 操作规则 |
|---|---|
| `id` | 新关系使用 UUID；编辑关系保留 ID |
| `sourceId` / `targetId` | 引用当前 JSON 已有且不同的书签 ID |
| `kind` | `CALLS` 调用、`IMPLEMENTS` 实现、`DESIGN` 设计依据、`RELATED` 双向相关 |
| `note` | 给出源码依据和关系说明；未知或推测关系明确标注 |
| 去重 | 相同起点、终点、类型只保留一条；`RELATED` 反向也算重复 |
| 环 | 关系图允许环；分组树仍禁止环 |
| 删除／归并 | 删除最后引用时删除相连关系；归并时重映射端点并清理自关联及重复边 |

需要可点击交叉引用时，在描述里写 `[阅读下一步](bookmark://目标ID)`。ID 含中文或保留字符时应使用 URI 编码；普通 UUID 可直接使用。IDE 合并导入时会重映射采用的导入描述中的链接；离线手动归并 ID 时，AI 也必须更新这些内部链接。目标未包含在分享范围内的链接不会自动补齐目标。

关系和说明随 JSON 分享，复核基线保持个人状态。AI 不能通过编辑 `BookmarkReadingState` 把未经人工确认的源码静默设为已复核。

## 5. 当前项目：导出、校验、写回

下面命令在本插件源码仓库执行。目标项目先在 IDEA 中保存并关闭；路径替换为实际绝对路径。

仅只读审查磁盘状态时，不必关闭项目，但这不包含 IDEA 尚未保存的编辑缓冲区。任何实际写回前，必须先保存并关闭目标项目，重新导出最新状态，保留最新 `_source`，再整理和应用，不能复用先前的只读快照写回。

```bash
python3 tools/bookmarks_store.py export \
  --project "/项目绝对路径" \
  --out "/临时目录/bookmarks-plan.json"
```

导出的 JSON 包含 `formatVersion`、`bookmarks`、`groups`、格式 3 的 `relations` 和 `_source`。`_source` 记录源文件路径、SHA-256、项目路径、Git 分支与提交号。**保留 `_source` 原值**，它用于拒绝过期计划和错误项目写入。

下面的格式 3 示例保留一个书签的跨组引用，并连接另一书签。实际操作应修改导出文件，保留工具生成的 `_source`，不要把此片段当成可直接写回的完整文件。`lineText` 在示例中省略，实际定位仍须精确核验。

```json
{
  "formatVersion": 3,
  "bookmarks": [
    {"id":"bookmark-1","provider":"com.intellij.ide.bookmark.providers.LineBookmarkProvider","attributes":{"url":"file://$PROJECT_DIR$/src/Reader.java","line":"0"},"type":"DEFAULT","descriptionMarkdown":"读取入口\n\n[查看下一阶段](bookmark://bookmark-2)"},
    {"id":"bookmark-2","provider":"com.intellij.ide.bookmark.providers.LineBookmarkProvider","attributes":{"url":"file://$PROJECT_DIR$/src/Reader.java","line":"10"},"type":"DEFAULT","descriptionMarkdown":"处理阶段"}
  ],
  "groups": [
    {"id":"root","parentId":null,"name":"同步链路","isDefault":true,"bookmarkIds":["bookmark-1","bookmark-2"]},
    {"id":"child","parentId":"root","name":"读取入口","isDefault":false,"bookmarkIds":["bookmark-1"]}
  ],
  "relations": [
    {"id":"edge-1","sourceId":"bookmark-1","targetId":"bookmark-2","kind":"CALLS","note":"读取入口调用处理阶段；按目标提交核验。"}
  ]
}
```

格式 2 导出继续保留 `formatVersion: 2`，不得添加 `relations` 字段。需要使用关系时，优先在新版插件中保存升级；手工升级计划必须显式改为 3 并加入 `relations`，保留原书签／分组 ID 与最新 `_source`。

编辑后先校验，再写回。`--project-closed` 表示执行者已核实目标项目关闭，它不会自动关闭 IDE。

```bash
python3 tools/bookmarks_store.py validate \
  --project "/项目绝对路径" \
  --input "/临时目录/bookmarks-plan.json"
python3 tools/bookmarks_store.py apply \
  --project "/项目绝对路径" \
  --input "/临时目录/bookmarks-plan.json" \
  --project-closed
```

工具会核对源版本和 Git 状态、保留其他 XML 组件、备份原文件并原子替换。源文件、分支或提交变化后，重新导出并合并计划；不要修改 `_source.sha256` 绕过校验。文件锁用于防止多个 AI 工具同时写入，不能代替关闭 IDEA。

`validate` 不只检查 JSON：它解析项目路径宏，检查普通本地行书签的文件、行号和可选 `lineText`。报告分别列出有效行书签数 `checkedLineBookmarks`、精确匹配的文本锚点数 `checkedTextAnchors`、未提供锚点数 `lineBookmarksWithoutText`。`--project` 可从 `_source.project` 推断；没有此元数据的 JSON 必须显式指定目标目录。错误会汇总书签 ID、文件、0 起始行号及 IDEA 显示行号。`apply` 即使没有先运行 `validate`，也会在备份和写回前再次执行定位校验，错误锚点不会写入。

文件书签（省略 `line` 或为 `-1`）、其他 provider 和非本地 URL 保留原有语义，结果中会分别列出跳过数量；跳过不代表已经验证能跳转。导出仍允许读取无效锚点，便于审查和修复，不会自动修改它们。

重新打开项目后，检查分组树、几个实际跳转位置、共享描述和删除范围。当前项目的新状态在下一次原版分支上下文保存时进入该分支归档。

## 6. 指定 Git 分支历史：导出和写回

先找到实际的 `.contexts.zip`。工具读取原版 `__branch_context_分支名` 条目，不执行 Git checkout，也不改其他分支条目。

```bash
python3 tools/bookmarks_store.py branches --archive "/IDE配置目录/tasks/实际项目名.contexts.zip"
python3 tools/bookmarks_store.py export \
  --project "/项目绝对路径" \
  --archive "/IDE配置目录/tasks/实际项目名.contexts.zip" \
  --branch "feature/example" \
  --out "/临时目录/branch-bookmarks.json"
```

按目标分支的源码核对定位，保留导出文件的 `_source`。旧归档若还没有格式 2 或 3，先在新版 IDEA 插件中恢复、保存该上下文；工具会拒绝直接改写旧格式。

关闭 IDEA 后执行以下命令。

```bash
python3 tools/bookmarks_store.py validate \
  --project "/项目绝对路径" \
  --input "/临时目录/branch-bookmarks.json"
python3 tools/bookmarks_store.py apply \
  --project "/项目绝对路径" \
  --archive "/IDE配置目录/tasks/实际项目名.contexts.zip" \
  --branch "feature/example" \
  --input "/临时目录/branch-bookmarks.json" \
  --project-closed
```

归档模式只替换指定条目中的书签组件，其他分支、打开文件等上下文内容保留，原归档先备份。原归档变化后需要重新导出。重新启动 IDEA 后，通过原版分支切换恢复并验证目标快照。

对于 Git 项目，归档定位校验会固定目标分支当时解析到的提交，并用 `git show` 读取该版本的仓库内源码，不切换或修改当前工作树。目标 Git ref 不存在时会拒绝操作，不能用当前另一分支的源码冒充；仓库外文件仍检查磁盘内容。非 Git 项目的归档只能按指定项目的当前磁盘文件核验。

如果修改的是当前活动分支的历史快照，当前项目文件仍可能是旧状态。应在 IDEA 中切到其他分支后关闭、编辑目标历史，再切回恢复，避免离开目标分支时用旧的当前状态覆盖刚整理好的历史。

Git 分支改名后，原机制不会自动把旧上下文重命名；先保留旧归档，再在新分支恢复、整理并保存新上下文。删除 Git 分支也不代表授权清空书签历史。工具不会替用户执行分支删除、历史清理或 Git 推送。

## 7. 删除、归并与验收

分类调整默认保留其他组的引用。删除动作按以下规则实施。

| 动作 | AI 应修改的内容 |
|---|---|
| 从一个组移除 | 只删该组的引用；其他组、描述保留 |
| 移除最后一个引用 | 同时删除书签记录及相连关系，效果是彻底删除 |
| 彻底删除书签 | 删除书签记录、所有分组引用和相连关系 |
| 删除分组子树 | 删除全部后代组；只删除失去所有引用的书签记录 |
| 归并重复书签 | 保留一个 ID、合并有效描述、替换全部引用及关系端点，更新内部链接，再删除冗余记录 |

整理完成后输出目标项目、分支或归档项、核验提交、变更数量、保留的共享引用、备份位置及验证结果。不能只报告“JSON 格式正确”；至少复核实际代码位置，并在 IDE 恢复后确认树形结构与 Markdown。

验收必须分开记录下面三个层次，不能互相替代。

| 层次 | 必须确认 |
|---|---|
| 结构 | JSON、ID、层级、引用、关系端点／类型／去重、默认组和快捷标记有效 |
| 源码定位 | 文件存在、0 起始行号正确；提供的 `lineText` 与完整源码行逐字相等；报告已查数量及跳过范围 |
| IDEA 恢复与导航 | 选取有缩进、Tab、尾空白或中文的代表书签，确认没有整行置灰，双击或回车能打开正确文件并落到目标行；复核共享描述与引用 |

只对比 `lineText.strip() == source.strip()` 不构成定位验收。验收失败时停止写回或保留已有状态，不自动重建、覆盖现有书签。

可把以下任务约定交给 AI，补齐实际范围后执行。

```text
按 docs/AI_BOOKMARKS.md 和 docs/STORAGE.md 整理目标项目的书签。
先确认项目/worktree、Git 分支、提交号和写入范围，读取现有 ID、层级、描述与引用。
基于目标分支源码整理业务主题和阅读链路，复用 ID，使用多组引用，保留有效 Markdown 和关联。
格式 3 的 relations 随书签管理；归并 ID 时同步更新端点及 bookmark:// 链接。
保留个人阅读路线、进度、复核基线和去重源码快照，不伪造已复核状态。
line 从 0 开始；lineText 可省略，提供时必须是去掉换行符后的完整源码原文。
保留所有行首、行尾空格和 Tab，不能使用 strip/lstrip/rstrip 生成或比较锚点。
说明具体变更；通过 bookmarks_store.py 导出、校验、备份并原子写回。
写入前核实目标项目已关闭；修改分支归档时关闭 IDEA，限定精确归档项。
保留导出元数据，遇到源版本变化重新导出合并；不绕过校验、不顺带改代码或操作 Git 历史。
恢复到 IDEA 后复核分组树、共享描述和跳转位置，再报告结果与备份位置。
不能只检查 JSON 或去除空白后的文本；必须精确定位校验，并实际确认书签不整行置灰、可以导航。
```

## 8. IDE 内导入导出

插件 `.7` 起可以在项目打开时通过工具栏或右键菜单导入、导出 JSON；`.10` 使用格式 3 并支持格式 2 输入，导入前可预览合并差异。它与本指南的离线源文件写回流程用途不同；可移植 JSON、`_source` 元数据和分支范围的具体边界见 [导入导出说明](IMPORT_EXPORT.md)。离线 `apply` 的关闭项目与源校验要求保持不变。
