package com.intellij.ide.bookmark

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID

/** Version 3 adds typed ID-based relations to the normalized version-2 records. */
object BookmarkStorageFormat {
  const val VERSION = 3

  @JvmStatic fun copyRelation(source: BookmarkRelation) = BookmarkRelation().also {
    it.id = source.id; it.sourceId = source.sourceId; it.targetId = source.targetId; it.kind = source.kind; it.note = source.note
  }

  private fun copyBookmark(source: BookmarkState) = BookmarkState().also {
    it.id = source.id
    it.provider = source.provider
    it.description = source.description
    it.type = source.type
    it.attributes.putAll(source.attributes)
  }

  private fun copyGroup(source: GroupState) = GroupState().also {
    it.id = source.id
    it.parentId = source.parentId
    it.name = source.name
    it.isDefault = source.isDefault
  }

  @JvmStatic
  fun normalize(legacy: ManagerState): ManagerState {
    if (legacy.formatVersion in 2..VERSION) {
      validate(legacy)
      return if (legacy.formatVersion == VERSION) legacy else BookmarkExchange.copy(legacy).apply { formatVersion = VERSION }
    }
    require(legacy.formatVersion == 0) { "Unsupported bookmark format: ${legacy.formatVersion}" }
    val result = ManagerState().apply { formatVersion = VERSION }
    val records = linkedMapOf<String, BookmarkState>()
    val identities = mutableMapOf<Pair<String?, Map<String, String>>, String>()
    legacy.groups.forEach { group ->
      val saved = copyGroup(group).apply { if (id.isNullOrBlank()) id = UUID.randomUUID().toString() }
      group.bookmarks.forEach { bookmark ->
        val identity = bookmark.provider to bookmark.attributes.toMap()
        val id = identities.getOrPut(identity) { bookmark.id ?: UUID.randomUUID().toString() }
        val record = records.getOrPut(id) { copyBookmark(bookmark).apply { this.id = id } }
        require(record.provider == bookmark.provider && record.attributes == bookmark.attributes) { "Bookmark ID collision: $id" }
        // Live manager snapshots already share descriptions. This also preserves
        // distinct old text if a caller normalizes an old exported snapshot.
        if (bookmark.description != null && bookmark.description != record.description) {
          record.description = listOfNotNull(record.description, bookmark.description).distinct().joinToString("\n\n---\n\n")
        }
        if (id !in saved.bookmarkIds) saved.bookmarkIds.add(id)
      }
      result.groups.add(saved)
    }
    result.bookmarks.addAll(records.values)
    validate(result)
    return result
  }

  @JvmStatic
  fun resolve(state: ManagerState): ManagerState {
    if (state.formatVersion == 0) return state // Original lists are repaired by the existing loader.
    validate(state) // Reject an invalid new document before touching the live model.
    val records = state.bookmarks.associateBy { it.id }
    return ManagerState().also { result ->
      state.relations.mapTo(result.relations, ::copyRelation)
      state.groups.forEach { group ->
        result.groups.add(copyGroup(group).apply {
          group.bookmarkIds.forEach { id -> bookmarks.add(copyBookmark(records.getValue(id))) }
        })
      }
    }
  }

  @JvmStatic
  fun validate(state: ManagerState) {
    require(state.formatVersion in 2..VERSION) { "Unsupported bookmark format: ${state.formatVersion}" }
    require(state.formatVersion == VERSION || state.relations.isEmpty()) { "Relations require formatVersion = 3" }
    val groups = state.groups.associateBy { it.id }
    val records = state.bookmarks.associateBy { it.id }
    require(groups.size == state.groups.size && groups.keys.all { !it.isNullOrBlank() }) { "Group IDs must be nonempty and unique" }
    require(records.size == state.bookmarks.size && records.keys.all { !it.isNullOrBlank() }) { "Bookmark IDs must be nonempty and unique" }
    require(state.groups.count { it.isDefault } <= 1) { "Only one default group is allowed" }
    val siblings = mutableSetOf<Pair<String?, String>>()
    val used = mutableSetOf<String>()
    state.groups.forEach { group ->
      require(group.name.isNotBlank() && siblings.add(group.parentId to group.name)) { "Sibling names must be nonempty and unique" }
      require(group.bookmarks.isEmpty()) { "Version 2 groups must contain references, not bookmark copies" }
      require(group.bookmarkIds.distinct().size == group.bookmarkIds.size) { "Duplicate bookmark reference in ${group.id}" }
      require(group.bookmarkIds.all { it in records }) { "Dangling bookmark reference in ${group.id}" }
      used.addAll(group.bookmarkIds)
      val ancestors = mutableSetOf(group.id)
      var parent = group.parentId
      while (parent != null) {
        require(parent in groups && ancestors.add(parent)) { "Missing parent or cycle in ${group.id}" }
        parent = groups.getValue(parent).parentId
      }
    }
    require(used == records.keys) { "Bookmarks without any group reference are not allowed" }
    val mnemonics = mutableSetOf<BookmarkType>()
    val locations = mutableSetOf<Pair<String?, Map<String, String>>>()
    state.bookmarks.forEach { bookmark ->
      require(!bookmark.provider.isNullOrBlank()) { "Bookmark provider is required" }
      require(bookmark.type == BookmarkType.DEFAULT || mnemonics.add(bookmark.type)) { "Duplicate mnemonic" }
      require(locations.add(bookmark.provider to bookmark.attributes.filterKeys { it != "lineText" })) { "Duplicate bookmark location; reuse its ID" }
      bookmark.attributes["line"]?.let { require(it.toIntOrNull()?.let { value -> value >= -1 } == true) { "Line must be zero-based, or -1 for a file" } }
    }
    BookmarkRelations.validate(state)
  }

  internal fun backupLegacy(project: Project, state: ManagerState) {
    if (state.formatVersion !in setOf(0, 2) || state.groups.isEmpty()) return
    val base = project.basePath ?: return
    val path = Path.of(base, ".idea", if (state.formatVersion == 2) "bookmarks-format2-backup.xml" else "bookmarks-legacy-backup.xml")
    if (Files.exists(path)) return
    Files.createDirectories(path.parent)
    val text = JDOMUtil.write(XmlSerializer.serialize(state))
    try { Files.writeString(path, text, StandardOpenOption.CREATE_NEW) }
    catch (_: java.nio.file.FileAlreadyExistsException) { /* Keep the first original backup. */ }
  }
}
