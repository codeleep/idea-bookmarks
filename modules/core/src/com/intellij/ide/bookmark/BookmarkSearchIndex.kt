package com.intellij.ide.bookmark

import java.util.Locale

/** Read-only index of canonical records in the current bookmark context. */
class BookmarkSearchIndex private constructor(val entries: List<Entry>) {
  data class Group(val id: String, val path: String)

  class Entry(val id: String, val location: String, val groups: List<Group>,
              private val description: String, private val anchor: String, text: String) {
    private val searchable = text.lowercase(Locale.ROOT)
    internal fun matches(words: List<String>): Boolean = words.all { it in searchable }
    fun snippet(query: String): String {
      val words = terms(query)
      val lines = (description.lineSequence() + sequenceOf(anchor)).filter { it.isNotBlank() }.toList()
      val line = if (words.isEmpty()) lines.firstOrNull() else lines.maxByOrNull { candidate ->
        val lower = candidate.lowercase(Locale.ROOT)
        words.count { it in lower }
      }
      val text = line.orEmpty()
      // Case conversion may expand Unicode characters; slice with original-text offsets.
      val match = words.map { text.indexOf(it, ignoreCase = true) }.filter { it >= 0 }.minOrNull() ?: 0
      val start = (match - 40).coerceAtLeast(0)
      return (if (start > 0) "…" else "") + MarkdownDescriptions.summary(text.substring(start))
    }
    fun preferredGroup(query: String): Group? {
      val words = terms(query)
      return groups.maxByOrNull { group ->
        val lower = group.path.lowercase(Locale.ROOT)
        words.count { it in lower }
      }
    }
  }

  fun search(query: String): List<Entry> {
    val words = terms(query)
    return entries.filter { it.matches(words) }
  }

  companion object {
    private val separator = Regex("\\s+")
    private fun terms(query: String) = query.trim().lowercase(Locale.ROOT).split(separator).filter { it.isNotEmpty() }

    @JvmStatic fun fromState(state: ManagerState, projectBase: String?): BookmarkSearchIndex {
      BookmarkStorageFormat.validate(state)
      val byId = state.groups.associateBy { it.id }
      val paths = state.groups.associate { group ->
        val names = mutableListOf(group.name)
        var parent = group.parentId
        while (parent != null) {
          val saved = byId.getValue(parent)
          names.add(saved.name); parent = saved.parentId
        }
        group.id!! to names.asReversed().joinToString(" / ")
      }
      val membership = mutableMapOf<String, MutableList<Group>>()
      state.groups.forEach { group -> group.bookmarkIds.forEach { id ->
        membership.getOrPut(id) { mutableListOf() }.add(Group(group.id!!, paths.getValue(group.id!!)))
      } }
      val base = projectBase?.replace('\\', '/')?.trimEnd('/')
      val entries = state.bookmarks.map { record ->
        val attributes = record.attributes
        val url = attributes["url"]
        var label = url?.removePrefix("file://") ?: attributes.filterKeys { it != "lineText" }.values.joinToString(" · ")
        if (base != null && label.startsWith("$base/")) label = label.removePrefix("$base/")
        if (label.startsWith("\$PROJECT_DIR\$/")) label = label.removePrefix("\$PROJECT_DIR\$/")
        attributes["line"]?.toIntOrNull()?.takeIf { it >= 0 }?.let { label += ":${it.toLong() + 1}" }
        val groups = membership.getValue(record.id!!).toList()
        val description = record.description.orEmpty()
        val anchor = attributes["lineText"].orEmpty()
        Entry(record.id!!, label, groups, description, anchor, buildString {
          append(description); append('\n'); append(label); append('\n')
          attributes.values.forEach { append(it); append('\n') }
          groups.forEach { append(it.path); append('\n') }
          if (record.type != BookmarkType.DEFAULT) append(record.type.mnemonic)
        })
      }
      return BookmarkSearchIndex(entries)
    }
  }
}
