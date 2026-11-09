package com.intellij.ide.bookmark

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializer
import com.intellij.util.xmlb.annotations.XCollection
import java.util.UUID

class BookmarkFilter {
  var query: String = ""
  var groupId: String = ""
  var directory: String = ""
  var currentFile: Boolean = false
  var description: String = "ANY"
  var health: String = "ANY"
  var kind: String = "ANY"
}

class BookmarkSavedView {
  var id: String = ""
  var name: String = ""
  var filter: BookmarkFilter = BookmarkFilter()
}

class BookmarkRoute {
  var groupId: String = ""
  var currentId: String = ""
  @get:XCollection val order: MutableList<String> = mutableListOf()
  @get:XCollection val completed: MutableList<String> = mutableListOf()
}

class BookmarkReadingState {
  @get:XCollection val routes: MutableList<BookmarkRoute> = mutableListOf()
  @get:XCollection val reviews: MutableList<BookmarkReview> = mutableListOf()
  @get:XCollection val sources: MutableList<BookmarkSourceSnapshot> = mutableListOf()
}

class BookmarkReview {
  var bookmarkId: String = ""
  var url: String = ""
  var snapshotId: String = ""
  var descriptionHash: String = ""
}

class BookmarkSourceSnapshot {
  var id: String = ""
  var text: String = ""
}

class BookmarkWorkspaceData {
  var reading: BookmarkReadingState = BookmarkReadingState()
  @get:XCollection val views: MutableList<BookmarkSavedView> = mutableListOf()
}

/** Personal views live in workspace.xml; reading state is additionally carried by the original branch context. */
@Service(Service.Level.PROJECT)
@State(name = "BookmarksWorkspace", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class BookmarkWorkspaceStore(private val project: Project) : PersistentStateComponent<BookmarkWorkspaceData> {
  private var data = BookmarkWorkspaceData()
  private var reviewRevision = 0L
  @Synchronized override fun getState(): BookmarkWorkspaceData = copy(data)
  @Synchronized override fun loadState(state: BookmarkWorkspaceData) { data = copy(state); reviewRevision++ }
  @Synchronized fun readingState(): BookmarkReadingState = copy(data.reading)
  @Synchronized fun restoreReading(state: BookmarkReadingState) { data.reading = copy(state); reviewRevision++ }
  @Synchronized fun reviewRevision(): Long = reviewRevision

  @Synchronized fun markReviewed(state: ManagerState, captures: List<BookmarkImpact.Capture>, expectedRevision: Long) {
    check(reviewRevision == expectedRevision) { "分支或复核基线已变化，请刷新后重试" }
    val manager = BookmarksManager.getInstance(project) as? BookmarksManagerImpl ?: error("书签服务不可用")
    check(!manager.isRestoringBookmarks() && BookmarkExchange.toJson(manager.captureForExchange(), null) == BookmarkExchange.toJson(state, null)) { "书签上下文已变化，请重新复核" }
    check(com.intellij.openapi.application.ReadAction.compute<Boolean, RuntimeException> { BookmarkImpact.stillCurrent(project, captures) }) { "源码已变化，请重新查看差异" }
    val records = state.bookmarks.associateBy { it.id }
    require(captures.isNotEmpty() && captures.map { it.bookmarkId }.distinct().size == captures.size) { "请选择需要复核的书签" }
    val updated = copy(data.reading)
    captures.forEach { capture ->
      require(capture.bookmarkId in records && capture.text.length <= BookmarkImpact.MAX_FILE_CHARS) { "书签不存在或文件过大" }
      updated.reviews.removeIf { it.bookmarkId == capture.bookmarkId }
      updated.reviews.add(BookmarkReview().also {
        it.bookmarkId = capture.bookmarkId; it.url = capture.url; it.snapshotId = capture.hash
        it.descriptionHash = BookmarkImpact.hash(records.getValue(capture.bookmarkId).description.orEmpty())
      })
      if (updated.sources.none { it.id == capture.hash }) updated.sources.add(BookmarkSourceSnapshot().also { it.id = capture.hash; it.text = capture.text })
    }
    updated.reviews.removeIf { it.bookmarkId !in records }
    val hashes = updated.reviews.map { it.snapshotId }.toSet()
    updated.sources.removeIf { it.id !in hashes }
    require(updated.sources.sumOf { it.text.length.toLong() } <= BookmarkImpact.MAX_TOTAL_CHARS) { "复核快照容量已满，请先清除不再需要的基线" }
    data.reading = updated; reviewRevision++
  }

  @Synchronized fun clearReviews(ids: Set<String>, expectedRevision: Long) {
    check(reviewRevision == expectedRevision) { "分支或复核基线已变化，请刷新后重试" }
    data.reading.reviews.removeIf { it.bookmarkId in ids }
    val hashes = data.reading.reviews.map { it.snapshotId }.toSet()
    data.reading.sources.removeIf { it.id !in hashes }; reviewRevision++
  }
  @Synchronized fun views(): List<BookmarkSavedView> = data.views.map(::copy)

  @Synchronized fun saveView(name: String, filter: BookmarkFilter): BookmarkSavedView {
    val title = name.trim(); require(title.isNotEmpty()) { "视图名称不能为空" }
    require(data.views.none { it.name == title }) { "同名视图已存在，请删除后重新保存" }
    return BookmarkSavedView().also {
      it.id = UUID.randomUUID().toString(); it.name = title; it.filter = copy(filter)
      data.views.add(copy(it))
    }
  }
  @Synchronized fun removeView(id: String) { data.views.removeIf { it.id == id } }

  @Synchronized fun route(state: ManagerState, groupId: String): BookmarkRoute {
    val ids = routeIds(state, groupId)
    val route = data.reading.routes.firstOrNull { it.groupId == groupId } ?: BookmarkRoute().also {
      it.groupId = groupId; data.reading.routes.add(it)
    }
    val order = route.order.distinct().filter { it in ids }
    val completed = route.completed.distinct().filter { it in ids }
    route.order.clear(); route.order.addAll(order); ids.forEach { if (it !in route.order) route.order.add(it) }
    route.completed.clear(); route.completed.addAll(completed)
    if (route.currentId !in ids) route.currentId = route.order.firstOrNull().orEmpty()
    return copy(route)
  }

  @Synchronized fun updateRoute(state: ManagerState, proposed: BookmarkRoute) {
    val ids = routeIds(state, proposed.groupId)
    require(proposed.order.size == ids.size && proposed.order.toSet() == ids.toSet()) { "路线已变化，请刷新后重试" }
    require(proposed.completed.distinct().size == proposed.completed.size && proposed.completed.all { it in ids }) { "阅读进度引用了无效书签" }
    require(proposed.currentId.isEmpty() || proposed.currentId in ids) { "当前阅读位置无效" }
    data.reading.routes.removeIf { it.groupId == proposed.groupId }
    data.reading.routes.add(copy(proposed))
  }

  /** Order edits preserve the latest progress, even if it changed while the dialog was open. */
  @Synchronized fun reorderRoute(state: ManagerState, groupId: String, order: List<String>) {
    val ids = routeIds(state, groupId)
    require(order.size == ids.size && order.toSet() == ids.toSet()) { "路线已变化，请重新调整顺序" }
    val current = route(state, groupId)
    current.order.clear(); current.order.addAll(order)
    updateRoute(state, current)
  }

  companion object {
    @JvmStatic fun getInstance(project: Project): BookmarkWorkspaceStore = project.getService(BookmarkWorkspaceStore::class.java)
    @JvmStatic fun <T : Any> copy(value: T): T = XmlSerializer.deserialize(XmlSerializer.serialize(value), value.javaClass)

    /** Preorder of the selected group and descendants, canonical IDs appear only once. */
    @JvmStatic fun routeIds(state: ManagerState, groupId: String): List<String> {
      require(state.groups.any { it.id == groupId }) { "当前分支没有该分组" }
      val ids = linkedSetOf<String>()
      fun visit(id: String) {
        ids.addAll(state.groups.single { it.id == id }.bookmarkIds)
        state.groups.filter { it.parentId == id }.forEach { visit(it.id!!) }
      }
      visit(groupId); return ids.toList()
    }
  }
}
