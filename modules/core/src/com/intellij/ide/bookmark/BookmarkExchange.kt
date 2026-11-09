package com.intellij.ide.bookmark

import kotlinx.serialization.json.*
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Portable interchange uses the same version-2 schema as the offline AI tool. */
object BookmarkExchange {
  const val MAX_BYTES = 10 * 1024 * 1024
  private const val PROJECT_URL = "file://\$PROJECT_DIR\$"
  private val json = Json { prettyPrint = true }
  private const val VCS_PROVIDER = "com.intellij.platform.vcs.log.impl.bookmarks.VcsLogBookmarkProvider"
  private const val LINE_PROVIDER = "com.intellij.ide.bookmark.providers.LineBookmarkProvider"

  private fun validate(state: ManagerState) {
    BookmarkStorageFormat.validate(state)
    require(state.bookmarks.map { location(it) }.distinct().size == state.bookmarks.size) { "同一代码位置只能保存一条书签，请复用书签 ID" }
    require(state.bookmarks.filter { it.provider == LINE_PROVIDER }.all { !it.attributes["url"].isNullOrBlank() }) { "文件或行书签必须包含 url" }
  }

  private fun exportAttribute(provider: String?, key: String, value: String, base: Path?): String = when {
    key == "url" -> portable(value, base)
    provider == VCS_PROVIDER && key == "root" -> portable("file://$value", base).removePrefix("file://")
    else -> value
  }

  private fun importAttribute(provider: String?, key: String, value: String, base: Path?): String = when {
    key == "url" -> expanded(value, base)
    provider == VCS_PROVIDER && key == "root" -> expanded("file://$value", base).removePrefix("file://")
    else -> value
  }

  @JvmStatic fun copy(state: ManagerState): ManagerState = ManagerState().also { result ->
    result.formatVersion = state.formatVersion
    state.relations.mapTo(result.relations, BookmarkStorageFormat::copyRelation)
    state.bookmarks.mapTo(result.bookmarks) { saved -> BookmarkState().also {
      it.id = saved.id; it.provider = saved.provider; it.description = saved.description; it.type = saved.type
      it.attributes.putAll(saved.attributes)
    } }
    state.groups.mapTo(result.groups) { saved -> GroupState().also {
      it.id = saved.id; it.parentId = saved.parentId; it.name = saved.name; it.isDefault = saved.isDefault
      it.bookmarkIds.addAll(saved.bookmarkIds)
    } }
  }

  /** Null means all groups; selected subtrees become roots when their parent is not selected. */
  @JvmStatic fun select(state: ManagerState, roots: Set<String>?): ManagerState {
    validate(state)
    val result = copy(state)
    if (roots != null) {
      require(roots.isNotEmpty() && roots.all { id -> state.groups.any { it.id == id } }) { "请选择有效分组" }
      val included = roots.toMutableSet()
      var changed: Boolean
      do { changed = false; state.groups.forEach { if (it.parentId in included && included.add(it.id!!)) changed = true } } while (changed)
      result.groups.removeIf { it.id !in included }
      result.groups.forEach { if (it.parentId !in included) it.parentId = null }
      // Different selected roots may originally have equal names under different parents.
      val names = mutableSetOf<Pair<String?, String>>()
      result.groups.forEach { group ->
        val original = group.name; var suffix = 2
        while (!names.add(group.parentId to group.name)) group.name = "$original (${suffix++})"
      }
      val used = result.groups.flatMap { it.bookmarkIds }.toSet()
      result.bookmarks.removeIf { it.id !in used }
      result.relations.removeIf { it.sourceId !in used || it.targetId !in used }
    }
    validate(result)
    return result
  }

  private fun portable(url: String, base: Path?): String {
    if (base == null) return url
    val prefix = "file://" + base.toAbsolutePath().normalize().toString().replace('\\', '/').trimEnd('/')
    return if (url == prefix || url.startsWith("$prefix/")) PROJECT_URL + url.removePrefix(prefix) else url
  }

  private fun expanded(url: String, base: Path?): String {
    if (url != PROJECT_URL && !url.startsWith("$PROJECT_URL/")) return url
    require(base != null) { "项目相对路径需要目标项目目录" }
    val root = base.toAbsolutePath().normalize()
    val suffix = url.removePrefix(PROJECT_URL).removePrefix("/")
    require(!suffix.contains('\\') && suffix.split('/').none { it == ".." || it == "." }) { "项目相对路径不能包含路径穿越" }
    val resolved = root.resolve(suffix).normalize()
    require(resolved.startsWith(root)) { "项目相对路径超出项目目录" }
    return "file://" + resolved.toString().replace('\\', '/')
  }

  @JvmStatic fun toJson(state: ManagerState, projectBase: Path?): String {
    validate(state)
    val document = buildJsonObject {
      put("formatVersion", BookmarkStorageFormat.VERSION)
      putJsonArray("relations") { state.relations.forEach { edge -> add(buildJsonObject {
        put("id", edge.id); put("sourceId", edge.sourceId); put("targetId", edge.targetId); put("kind", edge.kind); put("note", edge.note)
      }) } }
      putJsonArray("bookmarks") { state.bookmarks.forEach { saved -> add(buildJsonObject {
        put("id", saved.id); put("provider", saved.provider); put("type", saved.type.name)
        put("descriptionMarkdown", saved.description?.let(::JsonPrimitive) ?: JsonNull)
        putJsonObject("attributes") { saved.attributes.forEach { (key, value) -> put(key, exportAttribute(saved.provider, key, value, projectBase)) } }
      }) } }
      putJsonArray("groups") { state.groups.forEach { saved -> add(buildJsonObject {
        put("id", saved.id); put("parentId", saved.parentId?.let(::JsonPrimitive) ?: JsonNull)
        put("name", saved.name); put("isDefault", saved.isDefault)
        putJsonArray("bookmarkIds") { saved.bookmarkIds.forEach { add(JsonPrimitive(it)) } }
      }) } }
    }
    return json.encodeToString(JsonElement.serializer(), document) + "\n"
  }

  private fun JsonElement.obj(fields: Set<String>, optional: Set<String> = emptySet()): JsonObject {
    val value = this as? JsonObject ?: error("需要 JSON 对象")
    require(value.keys.containsAll(fields) && value.keys.all { it in fields || it in optional }) { "JSON 字段缺失或包含未知字段" }
    return value
  }
  private fun JsonElement.text(): String = (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("需要字符串")
  private fun JsonElement.nullableText(): String? = if (this == JsonNull) null else text()
  private fun JsonElement.array(): JsonArray = this as? JsonArray ?: error("需要 JSON 数组")

  @JvmStatic fun fromJson(text: String, projectBase: Path?): ManagerState {
    require(text.toByteArray(StandardCharsets.UTF_8).size <= MAX_BYTES) { "JSON 文件不能超过 10 MiB" }
    checkJsonKeys(text) // The standard parser accepts duplicate keys; reject them before decoding.
    val document = json.parseToJsonElement(text).obj(setOf("formatVersion", "bookmarks", "groups"), setOf("_source", "relations"))
    val version = document.getValue("formatVersion").toString()
    require(version in setOf("2", "3")) { "仅支持 formatVersion = 2 或 3" }
    require(version == "3" || "relations" !in document) { "关联关系需要 formatVersion = 3" }
    val result = ManagerState().apply { formatVersion = BookmarkStorageFormat.VERSION }
    val bookmarks = document.getValue("bookmarks").array()
    val groups = document.getValue("groups").array()
    require(bookmarks.size <= 20000 && groups.size <= 5000) { "单次最多导入 20000 个书签、5000 个分组" }
    bookmarks.forEach { item ->
      val value = item.obj(setOf("id", "provider", "attributes", "type", "descriptionMarkdown"))
      result.bookmarks.add(BookmarkState().apply {
        id = value.getValue("id").text(); provider = value.getValue("provider").text()
        description = value.getValue("descriptionMarkdown").nullableText()
        type = BookmarkType.valueOf(value.getValue("type").text())
        val attributes = value.getValue("attributes") as? JsonObject ?: error("attributes 必须是字符串映射")
        attributes.forEach { (key, saved) -> this.attributes[key] = importAttribute(provider, key, saved.text(), projectBase) }
      })
    }
    groups.forEach { item ->
      val value = item.obj(setOf("id", "parentId", "name", "isDefault", "bookmarkIds"))
      result.groups.add(GroupState().apply {
        id = value.getValue("id").text(); parentId = value.getValue("parentId").nullableText(); name = value.getValue("name").text()
        val default = value.getValue("isDefault")
        require(default.toString() in setOf("true", "false")) { "isDefault 必须是布尔值" }
        isDefault = default.toString() == "true"
        value.getValue("bookmarkIds").array().mapTo(bookmarkIds) { it.text() }
      })
    }
    document["relations"]?.array()?.forEach { item ->
      val edge = item.obj(setOf("id", "sourceId", "targetId", "kind", "note"))
      result.relations.add(BookmarkRelation().apply {
        id = edge.getValue("id").text(); sourceId = edge.getValue("sourceId").text(); targetId = edge.getValue("targetId").text()
        kind = edge.getValue("kind").text(); note = edge.getValue("note").text()
      })
    }
    validate(result)
    return result
  }

  /** Bound nesting and reject duplicate keys, including escaped spellings of the same key. */
  private fun checkJsonKeys(text: String) {
    var index = 0
    fun space() { while (index < text.length && text[index].isWhitespace()) index++ }
    fun quoted(): String {
      val start = index++
      while (index < text.length) {
        when (text[index++]) {
          '\\' -> index++
          '"' -> return json.parseToJsonElement(text.substring(start, index)).jsonPrimitive.content
        }
      }
      error("JSON 字符串未结束")
    }
    fun value(depth: Int) {
      require(depth <= 128) { "JSON 嵌套过深" }; space()
      require(index < text.length) { "JSON 不完整" }
      when (text[index]) {
        '{' -> {
          index++; space(); val keys = mutableSetOf<String>()
          if (index < text.length && text[index] == '}') { index++; return }
          while (true) {
            space(); require(index < text.length && text[index] == '"') { "JSON 对象键无效" }
            require(keys.add(quoted())) { "JSON 存在重复字段" }; space()
            require(index < text.length && text[index++] == ':') { "JSON 缺少冒号" }
            value(depth + 1); space()
            require(index < text.length) { "JSON 对象未结束" }
            val end = text[index++]; if (end == '}') break
            require(end == ',') { "JSON 对象格式错误" }
          }
        }
        '[' -> {
          index++; space()
          if (index < text.length && text[index] == ']') { index++; return }
          while (true) {
            value(depth + 1); space(); require(index < text.length) { "JSON 数组未结束" }
            val end = text[index++]; if (end == ']') break
            require(end == ',') { "JSON 数组格式错误" }
          }
        }
        '"' -> quoted()
        else -> { val start = index; while (index < text.length && text[index] !in ",]} \r\n\t") index++; require(index > start) { "JSON 值无效" } }
      }
    }
    value(0); space(); require(index == text.length) { "JSON 包含多余内容" }
  }

  private fun location(bookmark: BookmarkState): Pair<String?, Map<String, String>> = bookmark.provider to
    if (bookmark.provider == LINE_PROVIDER) mapOf("url" to bookmark.attributes["url"].orEmpty(), "line" to (bookmark.attributes["line"]?.toIntOrNull() ?: -1).toString())
    else bookmark.attributes.filterKeys { it != "lineText" }

  class Plan(val state: ManagerState, val changes: List<String>, val descriptionConflicts: Int, val relationConflicts: Int)

  /** Merge never deletes/moves existing groups or removes their references. */
  @JvmStatic fun plan(current: ManagerState, incoming: ManagerState, replace: Boolean, preferImported: Boolean): Plan {
    validate(current); validate(incoming)
    val result = (if (replace) copy(incoming) else copy(current)).apply { formatVersion = BookmarkStorageFormat.VERSION }
    val changes = mutableListOf<String>()
    var conflicts = 0
    var relationConflicts = 0
    if (replace) {
      changes.add("替换当前上下文：现有 ${current.groups.size} 个分组、${current.bookmarks.size} 个书签 → 导入 ${incoming.groups.size} 个分组、${incoming.bookmarks.size} 个书签。")
      changes.add("当前内容会先自动备份；其他 Git 分支的已保存快照不变。")
    }
    else {
      val records = result.bookmarks.associateBy { location(it) }.toMutableMap()
      val bookmarkIds = result.bookmarks.mapTo(mutableSetOf()) { it.id!! }
      val types = result.bookmarks.mapTo(mutableSetOf()) { it.type }
      val remap = mutableMapOf<String, String>()
      val importedDescriptions = mutableSetOf<String>()
      incoming.bookmarks.forEach { source ->
        val existing = records[location(source)]
        if (existing != null) {
          remap[source.id!!] = existing.id!!
          if (preferImported && source.description != null && source.description == existing.description) importedDescriptions.add(existing.id!!)
          if (existing.description != source.description && source.description != null) {
            if (existing.description.isNullOrEmpty()) {
              existing.description = source.description; importedDescriptions.add(existing.id!!); changes.add("补充描述：${label(source)}")
            }
            else {
              conflicts++
              if (preferImported) { existing.description = source.description; importedDescriptions.add(existing.id!!) }
              changes.add("描述冲突（${if (preferImported) "采用导入" else "保留现有"}）：${label(source)}")
            }
          }
          if (source.type != BookmarkType.DEFAULT && source.type != existing.type) changes.add("同位置书签保留现有快捷标记：${label(source)}")
        }
        else {
          val saved = copy(ManagerState().apply { formatVersion = 2; bookmarks.add(source) }).bookmarks.single()
          if (saved.id in bookmarkIds) { saved.id = UUID.randomUUID().toString(); changes.add("书签 ID 冲突，分配新 ID：${label(source)}") }
          bookmarkIds.add(saved.id!!)
          if (saved.type != BookmarkType.DEFAULT && saved.type in types) {
            changes.add("快捷标记 ${saved.type} 已占用，导入为普通书签：${label(source)}"); saved.type = BookmarkType.DEFAULT
          }
          types.add(saved.type); result.bookmarks.add(saved); records[location(saved)] = saved; remap[source.id!!] = saved.id!!
          importedDescriptions.add(saved.id!!)
          changes.add("新增书签：${label(source)}")
        }
      }
      result.bookmarks.filter { it.id in importedDescriptions }.forEach { saved ->
        val before = saved.description
        saved.description = before?.let { BookmarkLinks.remap(it, remap) }
        if (saved.description != before) changes.add("更新描述中的书签链接：${label(saved)}")
      }
      val groupIds = result.groups.mapTo(mutableSetOf()) { it.id!! }
      val recordsById = result.bookmarks.associateBy { it.id }
      val siblings = result.groups.associateBy { it.parentId to it.name }.toMutableMap()
      val groupRemap = mutableMapOf<String, String>()
      val waiting = incoming.groups.toMutableList()
      while (waiting.isNotEmpty()) {
        val ready = waiting.filter { it.parentId == null || it.parentId in groupRemap }
        check(ready.isNotEmpty()) { "分组层级无效" }
        ready.forEach { source ->
          val parent = source.parentId?.let { groupRemap.getValue(it) }
          val saved = siblings[parent to source.name] ?: GroupState().also {
            it.id = source.id.takeUnless { id -> id in groupIds } ?: UUID.randomUUID().toString()
            it.parentId = parent; it.name = source.name
            result.groups.add(it); groupIds.add(it.id!!); siblings[parent to it.name] = it
            changes.add("新增分组：${groupPath(result, it)}")
          }
          if (source.isDefault && result.groups.none { it.isDefault }) saved.isDefault = true
          groupRemap[source.id!!] = saved.id!!
          source.bookmarkIds.forEach { id ->
            val mapped = remap.getValue(id)
            if (mapped !in saved.bookmarkIds) { saved.bookmarkIds.add(mapped); changes.add("加入分组：${groupPath(result, saved)} ← ${label(recordsById.getValue(mapped))}") }
          }
          waiting.remove(source)
        }
      }
      incoming.relations.forEach { original ->
        val edge = BookmarkStorageFormat.copyRelation(original).apply {
          sourceId = remap.getValue(original.sourceId); targetId = remap.getValue(original.targetId)
        }
        if (edge.sourceId == edge.targetId) changes.add("合并后关联两端为同一书签，跳过关联：${edge.id}")
        else {
          val existing = result.relations.firstOrNull { BookmarkRelations.key(it) == BookmarkRelations.key(edge) }
          if (existing != null) {
            if (existing.note != edge.note) {
              if (existing.note.isEmpty()) {
                existing.note = edge.note; changes.add("补充关联说明：${edge.id}")
              }
              else {
                relationConflicts++
                if (preferImported) existing.note = edge.note
                changes.add("关联说明冲突（${if (preferImported) "采用导入" else "保留现有"}）：${edge.id}")
              }
            }
          }
          else {
            if (result.relations.any { it.id == edge.id }) edge.id = UUID.randomUUID().toString()
            result.relations.add(edge); changes.add("新增关联：${edge.sourceId} → ${edge.targetId}（${BookmarkRelations.KINDS[edge.kind]}）")
          }
        }
      }
    }
    validate(result)
    if (changes.isEmpty()) changes.add("没有需要修改的内容。")
    return Plan(result, changes, conflicts, relationConflicts)
  }

  private fun groupPath(state: ManagerState, group: GroupState): String {
    val names = mutableListOf(group.name); var parent = group.parentId
    while (parent != null) { val value = state.groups.first { it.id == parent }; names.add(value.name); parent = value.parentId }
    return names.asReversed().joinToString(" / ")
  }

  private fun label(saved: BookmarkState): String {
    val url = saved.attributes["url"] ?: saved.attributes.entries.joinToString { "${it.key}=${it.value}" }
    val line = saved.attributes["line"]?.toIntOrNull()?.takeIf { it >= 0 }?.let { ":${it.toLong() + 1}" }.orEmpty()
    return "$url$line"
  }

  @JvmStatic fun toMarkdown(state: ManagerState, projectBase: Path?, projectName: String): String {
    validate(state)
    val byId = state.bookmarks.associateBy { it.id }
    fun escape(value: String): String = buildString {
      value.forEach { when (it) {
        '\n', '\r' -> append(' ')
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        '\\', '[', ']', '*', '_', '`', '!' -> { append('\\'); append(it) }
        else -> append(it)
      } }
    }
    return buildString {
      append("# ").append(escape(projectName)).append(" · 书签\n\n")
      append("共 ${state.groups.size} 个分组、${state.bookmarks.size} 个书签。多组中的同一书签共享描述。\n\n")
      append("代码链接相对项目根目录；将本文放在项目根目录可直接跳转。Markdown 描述保留原文。\n\n")
      state.groups.forEach { group ->
        append("## ").append(escape(groupPath(state, group))).append("\n\n")
        if (group.bookmarkIds.isEmpty()) append("（此分组没有直接书签）\n\n")
        group.bookmarkIds.forEach { id ->
          val saved = byId.getValue(id)
          val url = saved.attributes["url"]
          val shown = if (url == null) label(saved) else label(saved).replace(url, portable(url, projectBase).removePrefix("$PROJECT_URL/"))
          val relative = url?.let { portable(it, projectBase) }?.takeIf { it.startsWith("$PROJECT_URL/") }?.removePrefix("$PROJECT_URL/")
          val line = saved.attributes["line"]?.toIntOrNull()?.takeIf { it >= 0 }?.let { "#L${it.toLong() + 1}" }.orEmpty()
          append("### ")
          if (relative != null) {
            val encoded = relative.split('/').joinToString("/") { java.net.URLEncoder.encode(it, StandardCharsets.UTF_8).replace("+", "%20") }
            append('[').append(escape(shown)).append("](").append(encoded).append(line).append(')')
          }
          else append(escape(shown))
          append("\n\n")
          if (!saved.description.isNullOrEmpty()) append(saved.description).append("\n\n")
          else append("（暂无描述）\n\n")
        }
      }
      if (state.relations.isNotEmpty()) {
        append("## 书签关联\n\n")
        state.relations.forEach { edge ->
          append("- ").append(escape(label(byId.getValue(edge.sourceId)))).append(" → ")
            .append(escape(label(byId.getValue(edge.targetId)))).append("（").append(BookmarkRelations.KINDS[edge.kind]).append("）")
          if (edge.note.isNotEmpty()) append("：").append(escape(edge.note))
          append('\n')
        }
      }
    }
  }

  /** Same-directory atomic replacement avoids leaving a partial export after a failed write. */
  @JvmStatic fun write(path: Path, text: String) {
    val target = path.toAbsolutePath(); Files.createDirectories(target.parent)
    val temporary = Files.createTempFile(target.parent, ".bookmarks-", ".tmp")
    try {
      Files.writeString(temporary, text, StandardCharsets.UTF_8)
      Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
    finally { Files.deleteIfExists(temporary) }
  }
}
