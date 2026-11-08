package com.intellij.ide.bookmark

object BookmarkFilters {
  @JvmStatic fun select(state: ManagerState, base: String?, filter: BookmarkFilter,
                        currentFileUrl: String?, health: List<BookmarkHealth.Entry>): List<BookmarkSearchIndex.Entry> {
    val records = state.bookmarks.associateBy { it.id }
    val statuses = health.associateBy { it.id }
    val allowed = if (filter.groupId.isEmpty()) null else
      if (state.groups.any { it.id == filter.groupId }) BookmarkWorkspaceStore.routeIds(state, filter.groupId).toSet() else emptySet()
    val root = base?.replace('\\', '/')?.trimEnd('/')
    var directory = filter.directory.trim().replace('\\', '/').trimEnd('/')
    if (directory.startsWith("file://")) directory = directory.removePrefix("file://")
    if (directory.isNotEmpty() && !directory.startsWith("/")) directory = root?.let { "$it/$directory" } ?: "!missing-project!"
    fun url(record: BookmarkState): String = record.attributes["url"].orEmpty().replace("\$PROJECT_DIR\$", root.orEmpty())
    return BookmarkSearchIndex.fromState(state, base).search(filter.query).filter { entry ->
      val record = records.getValue(entry.id)
      val local = url(record)
      val path = local.removePrefix("file://")
      val isLine = record.provider == BookmarkHealth.LINE_PROVIDER && (record.attributes["line"]?.toIntOrNull() ?: -1) >= 0
      val isFile = record.provider == BookmarkHealth.LINE_PROVIDER && !isLine
      val status = statuses[entry.id]?.status
      (allowed == null || entry.id in allowed) &&
      (!filter.currentFile || currentFileUrl != null && local == currentFileUrl) &&
      (directory.isEmpty() || local.startsWith("file://") && (path == directory || path.startsWith("$directory/"))) &&
      when (filter.description) { "WITH" -> !record.description.isNullOrBlank(); "WITHOUT" -> record.description.isNullOrBlank(); "ANY" -> true; else -> false } &&
      when (filter.kind) { "LINE" -> isLine; "FILE" -> isFile; "OTHER" -> !isLine && !isFile; "ANY" -> true; else -> false } &&
      when (filter.health) {
        "VALID" -> status == BookmarkHealth.Status.HEALTHY
        "INVALID" -> status?.invalid == true
        "UNCHECKED" -> status == null || status == BookmarkHealth.Status.UNSUPPORTED
        "ANY" -> true
        else -> false
      }
    }
  }
}
