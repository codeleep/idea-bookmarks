package com.intellij.ide.bookmark

import java.util.UUID

object BookmarkRelations {
  @JvmField val KINDS = linkedMapOf("CALLS" to "调用", "IMPLEMENTS" to "实现", "DESIGN" to "设计依据", "RELATED" to "相关")
  @JvmStatic fun key(relation: BookmarkRelation): List<String> =
    listOf(relation.kind) + if (relation.kind == "RELATED") listOf(relation.sourceId, relation.targetId).sorted() else listOf(relation.sourceId, relation.targetId)

  @JvmStatic fun validate(state: ManagerState) {
    val ids = state.bookmarks.map { it.id }.toSet()
    val edgeIds = hashSetOf<String>(); val keys = hashSetOf<List<String>>()
    require(state.relations.size <= 50000) { "最多支持 50000 条书签关联" }
    state.relations.forEach {
      require(it.id.isNotBlank() && edgeIds.add(it.id)) { "关联 ID 不能为空或重复" }
      require(it.sourceId in ids && it.targetId in ids && it.sourceId != it.targetId) { "关联两端必须是当前上下文的不同书签" }
      require(it.kind in KINDS && keys.add(key(it))) { "关联类型无效或关系重复" }
      require(it.note.length <= 10000) { "关联说明不能超过 10000 个字符" }
    }
  }

  @JvmStatic fun connected(state: ManagerState, id: String): List<BookmarkRelation> = state.relations.filter { it.sourceId == id || it.targetId == id }

  @JvmStatic fun add(state: ManagerState, source: String, target: String, kind: String, note: String): ManagerState = BookmarkExchange.copy(state).apply {
    formatVersion = BookmarkStorageFormat.VERSION
    relations.add(BookmarkRelation().also { it.id = UUID.randomUUID().toString(); it.sourceId = source; it.targetId = target; it.kind = kind; it.note = note })
    BookmarkStorageFormat.validate(this)
  }

  @JvmStatic fun remove(state: ManagerState, relationId: String): ManagerState = BookmarkExchange.copy(state).apply {
    require(relations.removeIf { it.id == relationId }) { "关联已不存在，请刷新" }
    BookmarkStorageFormat.validate(this)
  }
}
