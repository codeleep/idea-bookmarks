// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.bookmark

import com.intellij.ide.bookmark.BookmarkBundle.message
import com.intellij.ide.bookmark.providers.FileBookmarkImpl
import com.intellij.ide.bookmark.providers.InvalidBookmark
import com.intellij.ide.bookmark.providers.LineBookmarkImpl
import com.intellij.ide.bookmark.providers.LineBookmarkProvider
import com.intellij.ide.bookmark.ui.BookmarksViewState
import com.intellij.ide.bookmark.ui.GroupCreateDialog
import com.intellij.ide.bookmark.ui.GroupSelectDialog
import com.intellij.ide.bookmark.ui.BookmarkDeletion
import com.intellij.ide.bookmarks.BookmarksListener
import com.intellij.ide.favoritesTreeView.FavoritesManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.PersistentStateComponentWithModificationTracker
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupManager
import com.intellij.openapi.ui.DoNotAskOption
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.util.io.systemIndependentPath
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.Invoker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.concurrency.CancellablePromise
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

private val LOG = Logger.getInstance(BookmarksManager::class.java)

@State(name = "BookmarksManager", storages = [Storage("bookmarks.xml"), Storage(StoragePathMacros.PRODUCT_WORKSPACE_FILE, deprecated = true)])
class BookmarksManagerImpl @ApiStatus.Internal constructor(private val project: Project, private val coroutineScope: CoroutineScope) :
  BookmarksManager, PersistentStateComponentWithModificationTracker<ManagerState> {

  private val invoker = Invoker.forBackgroundThreadWithReadAction(project)
  private val notifier = ModificationNotifier(project)
  private val allBookmarks = mutableMapOf<Bookmark, InManagerInfo>()
  private val allGroups = mutableListOf<Group>()
  private val relations = mutableListOf<BookmarkRelation>()
  private val pendingRestores = AtomicInteger()
  @Volatile private var restoreGeneration = 0L
  private var restoringSnapshot: ManagerState? = null
  private val unresolvedBookmarks = mutableMapOf<String, MutableList<BookmarkState>>()

  fun isRestoringBookmarks(): Boolean = pendingRestores.get() > 0
  fun getBookmarkId(bookmark: Bookmark): String? = synchronized(notifier) { allBookmarks[bookmark]?.id }

  init { com.intellij.ide.bookmark.context.BookmarkContextProvider.install() }
  private var defaultGroup: Group? = null
    set(group) = synchronized(notifier) {
      val old = field
      if (old === group) return@synchronized // nothing to change
      group?.let {
        val index = allGroups.indexOf(it)
        if (index < 0) return@synchronized // group is not added
        if (index > 0) moveGroup(index, 0) // rearrange groups
      }
      field = group
      notifier.defaultGroupChanged(old, group)
    }

  private val groupLineBookmarks
    get() = BookmarksViewState.getInstance(project).groupLineBookmarks

  private val sortedProviders: List<BookmarkProvider>
    get() = when {
      project.isDisposed -> emptyList()
      else -> BookmarkProvider.EP.getExtensions(project).sortedByDescending { it.weight }
    }

  internal val snapshot: List<BookmarkOccurrence>
    get() = synchronized(notifier) {
      notifier.snapshot ?: mutableListOf<BookmarkOccurrence>().also {
        notifier.snapshot = it // update cached snapshot
        for (group in orderedGroups()) {
          for (bookmark in group.getBookmarks()) {
            it.add(BookmarkOccurrence(group, bookmark, it.size, it))
          }
        }
      }
    }

  override fun getStateModificationCount(): Long = notifier.count

  /** Capture/compare/apply under the model lock so an import cannot overwrite a newer context. */
  fun captureForExchange(): ManagerState = synchronized(notifier) {
    check(!isRestoringBookmarks()) { "书签正在恢复，请稍后重试" }
    BookmarkExchange.copy(state)
  }

  fun applyExchange(expected: ManagerState, revision: Long, imported: ManagerState): java.nio.file.Path = synchronized(notifier) {
    check(!isRestoringBookmarks() && notifier.count == revision &&
          BookmarkExchange.toJson(state, null) == BookmarkExchange.toJson(expected, null)) {
      "书签或分支在预览期间发生了变化，请重新导入并预览"
    }
    BookmarkStorageFormat.validate(imported)
    val base = java.nio.file.Path.of(requireNotNull(project.basePath) { "项目没有可用的目录" })
    val backup = base.resolve(".idea/bookmarks-import-backups/bookmarks-${System.currentTimeMillis()}-${UUID.randomUUID()}.json")
    BookmarkExchange.write(backup, BookmarkExchange.toJson(expected, base))
    com.intellij.ide.bookmark.ui.MarkdownDescriptionEditor.getInstance(project).closeForImport()
    loadState(imported)
    backup
  }

  override fun getState(): ManagerState = synchronized(notifier) {
    if (isRestoringBookmarks()) restoringSnapshot?.let { return@synchronized it }
    restoringSnapshot = null
    BookmarkStorageFormat.normalize(ManagerState().apply { orderedGroups().mapTo(groups) { it.getState() } }).also { result ->
      val ids = result.bookmarks.map { it.id }.toSet()
      relations.filter { it.sourceId in ids && it.targetId in ids }.mapTo(result.relations, BookmarkStorageFormat::copyRelation)
    }
  }

  override fun loadState(state: ManagerState) {
    val resolved = BookmarkStorageFormat.resolve(state)
    // A normalized record has one identity shared by every group. Resolve its provider once,
    // so a later reference cannot replace an earlier one with a newly created bookmark.
    val restoredBookmarks = if (state.formatVersion >= 2) mutableMapOf<String, Bookmark?>() else null
    BookmarkStorageFormat.backupLegacy(project, state)
    synchronized(notifier) {
      restoreGeneration++
      remove()
      resolved.relations.mapTo(relations, BookmarkStorageFormat::copyRelation)
      restoringSnapshot = state
      val loaded = resolved.groups.map { saved ->
        val id = saved.id?.takeIf { it.isNotBlank() && allGroups.none { group -> group.id == it } } ?: UUID.randomUUID().toString()
        saved to Group(saved.name.ifBlank { project.name }, saved.isDefault, false, id)
      }
      val ids = allGroups.mapTo(hashSetOf()) { it.id }
      loaded.forEach { (saved, group) ->
        group.parentId = saved.parentId?.takeIf { it in ids && it != group.id }
      }
      // Old flat lists become roots. Broken parent links and cycles must never
      // hide bookmarks or make tree traversal recurse forever.
      allGroups.forEach { group ->
        val visited = hashSetOf(group.id)
        var parent = group.parentId
        while (parent != null) {
          if (!visited.add(parent)) {
            group.parentId = null
            break
          }
          parent = allGroups.find { it.id == parent }?.parentId
        }
      }
      val names = hashSetOf<Pair<String?, String>>()
      loaded.forEach { (saved, group) ->
        val original = group.name
        var suffix = 2
        while (!names.add(group.parentId to group.name)) group.name = "$original (${suffix++})"
        saved.bookmarks.forEach { bookmark -> group.addLater(bookmark, bookmark.type, bookmark.description, restoredBookmarks) }
      }
      notifier.groupsSorted()
    }
  }

  override fun noStateLoaded() {
    val group = addOrReuseGroup(project.name)
    val listener = object : BookmarksListener {
      override fun bookmarkAdded(old: com.intellij.ide.bookmarks.Bookmark) {
        group.addLater(old, old.type, old.description)
      }
    }
    project.messageBus.connect().subscribe(BookmarksListener.TOPIC, listener)
    StartupManager.getInstance(project).runAfterOpened {
      LOG.info("no state loaded for new bookmarks")
      com.intellij.ide.bookmarks.BookmarkManager.getInstance(project).allBookmarks.forEach { listener.bookmarkAdded(it) }
      invoker.invokeLater { noStateLoaded(FavoritesManager.getInstance(project)) }
    }
  }

  private fun noStateLoaded(manager: FavoritesManager) {
    for (name in manager.availableFavoritesListNames) {
      val group = addOrReuseGroup(name)
      for (item in manager.getFavoritesListRootUrls(name)) {
        group.addLater(item.data.first, BookmarkType.DEFAULT, null)
      }
    }
  }

  private fun findGroup(name: String) = synchronized(notifier) {
    findSibling(name, null) ?: allGroups.find { it.name == name }
  }

  private fun findSibling(name: String, parentId: String?): Group? =
    allGroups.find { it.parentId == parentId && it.name == name }

  private fun orderedGroups(): List<Group> = buildList {
    fun append(parentId: String?) {
      allGroups.filter { it.parentId == parentId }.forEach { add(it); append(it.id) }
    }
    append(null)
  }

  fun getChildGroups(parent: BookmarkGroup?): List<BookmarkGroup> = synchronized(notifier) {
    if (parent != null && !contains(parent)) return@synchronized emptyList()
    allGroups.filter { it.parentId == (parent as? Group)?.id }
  }

  fun getGroupId(group: BookmarkGroup): String? = synchronized(notifier) { (group as? Group)?.takeIf { contains(it) }?.id }

  fun getGroupById(id: String): BookmarkGroup? = synchronized(notifier) { allGroups.find { it.id == id } }

  fun getParentGroup(group: BookmarkGroup): BookmarkGroup? = synchronized(notifier) {
    (group as? Group)?.parentId?.let { id -> allGroups.find { it.id == id } }
  }

  fun isGroupInSubtree(group: BookmarkGroup, root: BookmarkGroup): Boolean = synchronized(notifier) {
    var current = group as? Group
    while (current != null) {
      if (current === root) return@synchronized true
      current = current.parentId?.let { id -> allGroups.find { it.id == id } }
    }
    false
  }

  fun getGroupPath(group: BookmarkGroup): String = synchronized(notifier) {
    val names = mutableListOf<String>()
    var current: BookmarkGroup? = group
    while (current != null) {
      names.add(current.name)
      current = getParentGroup(current)
    }
    names.asReversed().joinToString(" / ")
  }

  fun isGroupNameAvailable(name: String, parent: BookmarkGroup?, except: BookmarkGroup?): Boolean = synchronized(notifier) {
    name.isNotBlank() && allGroups.none { it !== except && it.parentId == (parent as? Group)?.id && it.name == name }
  }

  fun addSubgroup(parent: BookmarkGroup, name: String, isDefault: Boolean): BookmarkGroup? = synchronized(notifier) {
    if (!contains(parent) || !isGroupNameAvailable(name, parent, null)) return@synchronized null
    Group(name, isDefault, false, parentId = (parent as Group).id)
  }?.apply { notifier.selectLater { it.select(this) } }

  fun canSetParentGroup(group: BookmarkGroup, parent: BookmarkGroup?): Boolean = synchronized(notifier) {
    contains(group) && (parent == null || contains(parent) && !isGroupInSubtree(parent, group)) &&
      isGroupNameAvailable(group.name, parent, group)
  }

  fun setParentGroup(group: BookmarkGroup, parent: BookmarkGroup?): Boolean = synchronized(notifier) {
    if (!canSetParentGroup(group, parent)) return@synchronized false
    val value = group as Group
    val parentId = (parent as? Group)?.id
    if (value.parentId != parentId) {
      value.parentId = parentId
      notifier.groupsSorted()
      notifier.selectLater { it.select(group) }
    }
    true
  }

  fun canReparentGroups(groups: List<BookmarkGroup>, parent: BookmarkGroup?): Boolean = synchronized(notifier) {
    groups.isNotEmpty() && groups.distinct().size == groups.size && groups.map { it.name }.distinct().size == groups.size &&
      groups.all { canSetParentGroup(it, parent) } && groups.none { getParentGroup(it) === parent } &&
      groups.none { a -> groups.any { b -> a !== b && isGroupInSubtree(a, b) } }
  }

  fun reparentGroups(groups: List<BookmarkGroup>, parent: BookmarkGroup?): Boolean = synchronized(notifier) {
    if (!canReparentGroups(groups, parent)) return@synchronized false
    groups.forEach { setParentGroup(it, parent) }
    true
  }

  override fun createBookmark(context: Any?): Bookmark? = when (context is BookmarkState) {
    true -> sortedProviders.firstOrNull { it::class.java.name == context.provider }?.createBookmark(context.attributes)
    else -> sortedProviders.firstNotNullOfOrNull { it.createBookmark(context) }
  }

  private fun createDescription(bookmark: Bookmark): String {
    val text = when (bookmark) {
      is LineBookmark -> LineBookmarkProvider.Util.readLineText(bookmark)
      is InvalidBookmark -> bookmark.expectedText
      else -> null
    }
    return text?.trim() ?: ""
  }

  override fun getBookmarks(): List<Bookmark> = synchronized(notifier) { allBookmarks.keys.toList() }

  override fun getDefaultGroup(): BookmarkGroup? = synchronized(notifier) { defaultGroup }

  override fun getGroup(name: String): BookmarkGroup? = findGroup(name)

  override fun getGroups(): List<BookmarkGroup> = synchronized(notifier) { orderedGroups() }

  override fun getGroups(bookmark: Bookmark): List<BookmarkGroup> = synchronized(notifier) {
    allBookmarks[bookmark]?.groups?.let { orderedGroups().filter(it::contains) } ?: emptyList()
  }

  override fun addGroup(name: String, isDefault: Boolean): BookmarkGroup? = when {
    name.isBlank() -> null
    else -> synchronized(notifier) { if (findSibling(name, null) != null) null else Group(name, isDefault, true) }
  }?.apply { notifier.selectLater { it.select(this) } }

  private fun addOrReuseGroup(name: String, isDefaultState: Boolean? = null) = synchronized(notifier) {
    findSibling(name, null)?.apply { isDefaultState?.let { isDefault = it } } ?: Group(name, isDefaultState ?: false, false)
  }

  override fun getBookmark(type: BookmarkType): Bookmark? = synchronized(notifier) {
    findInfo(type)?.bookmark
  }

  private fun findInfo(type: BookmarkType) = when (type) {
    BookmarkType.DEFAULT -> null
    else -> allBookmarks.values.find { it.type == type }
  }

  override fun getAssignedTypes(): MutableSet<BookmarkType> = synchronized(notifier) {
    allBookmarks.values.map { it.type }.filterTo(mutableSetOf()) { it != BookmarkType.DEFAULT }
  }

  override fun getType(bookmark: Bookmark): BookmarkType? = synchronized(notifier) {
    allBookmarks[bookmark]?.type
  }

  override fun setType(bookmark: Bookmark, type: BookmarkType) {
    if (!canRewriteType(type, bookmark)) return
    synchronized(notifier) {
      val info = allBookmarks[bookmark] ?: return
      if (info.type == type) return
      rewriteType(type, bookmark)
      info.changeType(type)
    }
  }

  override fun toggle(bookmark: Bookmark, type: BookmarkType): Unit =
    getType(bookmark)?.let { if (it != type) setType(bookmark, type) else remove(bookmark) } ?: add(bookmark, type)

  override fun add(bookmark: Bookmark, type: BookmarkType) {
    synchronized(notifier) {
      // if all groups are removed we should add default group
      if (allGroups.isEmpty()) addOrReuseGroup(project.name)
    }
    val groups = findGroupsToAdd(bookmark) ?: return
    chooseGroupToAdd(groups) { group ->
      group.add(bookmark, type, bookmark.prepareDefaultDescription())
    }
  }

  private fun findGroupsToAdd(bookmark: Bookmark) = synchronized(notifier) {
    val info = allBookmarks[bookmark]
    when {
      info == null -> defaultGroup?.let { listOf(it) } ?: orderedGroups()
      else -> allGroups.filter { !info.groups.contains(it) }
    }
  }

  private fun chooseGroupToAdd(groups: List<Group>, addHandler: (Group) -> Unit) {
    if (groups.size == 1) {
      addHandler(groups[0])
      return
    }

    coroutineScope.launch(Dispatchers.EDT) {
      val group = if (groups.isEmpty()) {
        GroupCreateDialog(project, null, this@BookmarksManagerImpl).showAndGetGroup(true)
      }
      else {
        GroupSelectDialog(project, null, this@BookmarksManagerImpl, groups).showAndGetGroup(true)
      }
      group?.let {
        coroutineScope.launch(Dispatchers.Default) {
          addHandler(it as Group)
        }
      }
    }
  }

  override fun remove(bookmark: Bookmark) {
    val groups = findGroupsToRemove(bookmark)
    if (groups.isNullOrEmpty()) return

    if (groups.size == 1) {
      removeFromGroup(groups[0], bookmark)
    }
    else {
      coroutineScope.launch(Dispatchers.EDT) {
        val current = groups.firstOrNull { it === defaultGroup } ?: groups.first()
        BookmarkDeletion.delete(project, this@BookmarksManagerImpl, listOf(BookmarkOccurrence(current, bookmark)))
      }
    }
  }

  /** Update only membership, preserving the shared bookmark, mnemonic and Markdown. */
  fun setBookmarkGroups(bookmark: Bookmark, groups: List<BookmarkGroup>): Boolean = synchronized(notifier) {
    val selected = groups.distinct()
    if (selected.isEmpty() || selected.any { !contains(it) }) return false
    val previous = getGroups(bookmark)
    val type = allBookmarks[bookmark]?.type ?: BookmarkType.DEFAULT
    val description = allBookmarks[bookmark]?.description
    selected.filter { it !in previous }.forEach {
      if (!(it as Group).add(bookmark, type, description, 0)) return false
    }
    previous.filter { it !in selected }.forEach { removeFromGroup(it as Group, bookmark) }
    true
  }

  fun deleteOccurrences(occurrences: List<BookmarkOccurrence>, everywhere: Boolean) = synchronized(notifier) {
    if (everywhere) occurrences.map { it.bookmark }.distinct().forEach { removeFromAllGroups(it) }
    else occurrences.distinct().forEach { (it.group as? Group)?.let { group -> removeFromGroup(group, it.bookmark) } }
  }

  private fun findGroupsToRemove(bookmark: Bookmark) = synchronized(notifier) {
    allBookmarks[bookmark]?.groups?.let { allGroups.filter(it::contains) }
  }

  private fun removeFromAllGroups(bookmark: Bookmark) = synchronized(notifier) {
    findGroupsToRemove(bookmark)?.forEach { removeFromGroup(it, bookmark) }
  }

  private fun removeFromGroup(group: Group, bookmark: Bookmark): Pair<InManagerInfo, InGroupInfo?>? = synchronized(notifier) {
    val info = allBookmarks[bookmark] ?: return null
    if (!info.groups.remove(group)) return null
    val removed = info.groups.isEmpty()
    if (removed) {
      allBookmarks.remove(bookmark)
      relations.removeIf { it.sourceId == info.id || it.targetId == info.id }
    }
    val result = info to group.removeInfo(bookmark)
    info.bookmarkRemoved(group, removed)
    return result
  }

  override fun remove(): Unit = synchronized(notifier) {
    restoreGeneration++
    restoringSnapshot = null
    unresolvedBookmarks.clear()
    relations.clear()
    while (allGroups.isNotEmpty()) allGroups[0].remove()
  }

  private fun canRewriteType(type: BookmarkType, allowed: Bookmark): Boolean {
    if (BookmarksViewState.getInstance(project).rewriteBookmarkType) return true
    val bookmark = getBookmark(type) ?: return true
    if (bookmark == allowed) return true
    return MessageDialogBuilder
      .okCancel(message("bookmark.type.confirmation.title"), when (bookmark) {
        is LineBookmark -> message("bookmark.type.confirmation.line.bookmark", type.mnemonic, bookmark.file.presentableName, bookmark.line + 1)
        is FileBookmark -> message("bookmark.type.confirmation.file.bookmark", type.mnemonic, bookmark.file.presentableName)
        else -> message("bookmark.type.confirmation.bookmark", type.mnemonic)
      })
      .doNotAsk(object : DoNotAskOption.Adapter() {
        override fun rememberChoice(isSelected: Boolean, exitCode: Int) {
          BookmarksViewState.getInstance(project).rewriteBookmarkType = isSelected
        }
      })
      .yesText(message("bookmark.type.confirmation.button"))
      .asWarning()
      .ask(project)
  }

  private fun rewriteType(type: BookmarkType, allowed: Bookmark) {
    synchronized(notifier) {
      val info = findInfo(type) ?: return
      when (info.bookmark) {
        allowed -> return
        is LineBookmark -> removeFromAllGroups(info.bookmark)
        else -> info.changeType(BookmarkType.DEFAULT)
      }
    }
  }

  @ApiStatus.Internal
  fun sort(group: BookmarkGroup) {
    (group as? Group)?.sortLater()
  }

  private fun addGroupTo(index: Int, group: Group) = allGroups.add(index, group).also { notifier.groupAdded(group) }
  private fun removeGroupFrom(index: Int) = allGroups.removeAt(index).also { notifier.groupRemoved(it) }
  private fun moveGroup(fromIndex: Int, toIndex: Int) = addGroupTo(toIndex, removeGroupFrom(fromIndex))

  @ApiStatus.Internal
  fun move(group: BookmarkGroup, anchor: BookmarkGroup): Unit = synchronized(notifier) {
    if (getParentGroup(group) !== getParentGroup(anchor)) return
    if (group == defaultGroup || anchor == defaultGroup) return // cannot move default group
    val fromIndex = allGroups.indexOfFirst { it == group }
    if (fromIndex < 0) return // first group does not exist
    val toIndex = allGroups.indexOfFirst { it == anchor }
    if (toIndex < 0 || toIndex == fromIndex) return // second group does not exist or equal the first one
    moveGroup(fromIndex, toIndex)
  }

  @ApiStatus.Internal
  fun move(group: BookmarkGroup, bookmark: Bookmark, anchor: Bookmark) {
    (group as? Group)?.run { move(bookmark, anchor) }
  }

  override fun update(map: MutableMap<Bookmark, Bookmark?>) {
    while (map.isNotEmpty()) {
      val size = map.size
      val iterator = map.iterator()
      while (iterator.hasNext()) {
        val entry = iterator.next()
        val updated = entry.value?.let { updateInAllGroups(entry.key, it, map) }
        if (updated == null) removeFromAllGroups(entry.key)
        if (updated != false) iterator.remove()
      }
      if (map.size == size) break
    }
    if (map.isNotEmpty()) {
      val sb = StringBuilder("cannot resolve")
      for (entry in map) sb.append('\n').append(entry.key).append(" -> ").append(entry.value)
      //TODO:logging:println(sb)
    }
  }

  private fun updateInAllGroups(old: Bookmark, new: Bookmark, updatesMap: Map<Bookmark, Bookmark?>): Boolean = synchronized(notifier) {
    val oldInfo = allBookmarks[old] ?: return true
    val existingInfo = allBookmarks[new]
    
    if (existingInfo != null && existingInfo !== oldInfo) {
      if (updatesMap.containsKey(existingInfo.bookmark)) {
        return false
      }
      return mergeBookmarkIntoExisting(oldInfo, existingInfo)
    }
    
    return replaceBookmarkInAllGroups(oldInfo, old, new)
  }

  private fun mergeBookmarkIntoExisting(oldInfo: InManagerInfo, existingInfo: InManagerInfo): Boolean {
    val descriptions = listOfNotNull(existingInfo.description, oldInfo.description).distinct()
    existingInfo.description = descriptions.takeIf { it.isNotEmpty() }?.joinToString("\n\n---\n\n")
    val oldIterator = oldInfo.groups.iterator()
    while (oldIterator.hasNext()) {
      val group = oldIterator.next()
      val groupContainsNew = group.indexOf(existingInfo.bookmark) >= 0
      
      if (groupContainsNew) {
        group.removeInfo(oldInfo.bookmark)
      }
      else {
        if (group.updateInfo(oldInfo.bookmark, existingInfo.bookmark)) {
          if (!existingInfo.groups.contains(group)) {
            val initial = existingInfo.groups.isEmpty()
            existingInfo.groups.add(group)
            existingInfo.bookmarkAdded(group, initial)
          }
        }
      }
      
      oldIterator.remove()
      oldInfo.bookmarkRemoved(group, !oldIterator.hasNext())
    }
    allBookmarks.remove(oldInfo.bookmark)
    val remap = mapOf(oldInfo.id to existingInfo.id)
    allBookmarks.values.forEach { info ->
      val description = info.description ?: return@forEach
      val updated = BookmarkLinks.remap(description, remap)
      if (updated != description) info.groups.firstOrNull()?.setDescription(info.bookmark, updated)
    }
    relations.forEach {
      if (it.sourceId == oldInfo.id) it.sourceId = existingInfo.id
      if (it.targetId == oldInfo.id) it.targetId = existingInfo.id
    }
    val unique = linkedMapOf<List<String>, BookmarkRelation>()
    relations.filter { it.sourceId != it.targetId }.forEach { edge ->
      val previous = unique.putIfAbsent(BookmarkRelations.key(edge), edge)
      if (previous != null && edge.note.isNotEmpty() && edge.note != previous.note) {
        previous.note = listOf(previous.note, edge.note).filter { it.isNotEmpty() }.distinct().joinToString("\n\n---\n\n").take(10000)
      }
    }
    relations.clear(); relations.addAll(unique.values)
    existingInfo.groups.forEach { notifier.bookmarkChanged(it, existingInfo.bookmark) }
    return true
  }

  private fun replaceBookmarkInAllGroups(oldInfo: InManagerInfo, old: Bookmark, new: Bookmark): Boolean {
    allBookmarks.remove(old)
    val newInfo = InManagerInfo(new, oldInfo.type)
    newInfo.id = oldInfo.id
    newInfo.description = oldInfo.description
    
    val oldIterator = oldInfo.groups.iterator()
    while (oldIterator.hasNext()) {
      val group = oldIterator.next()
      if (group.updateInfo(old, new)) {
        newInfo.groups.add(group)
      }
      oldIterator.remove()
      oldInfo.bookmarkRemoved(group, !oldIterator.hasNext())
    }
    
    if (newInfo.groups.isNotEmpty()) {
      allBookmarks[new] = newInfo
      val newIterator = newInfo.groups.iterator()
      newInfo.bookmarkAdded(newIterator.next(), true)
      while (newIterator.hasNext()) {
        newInfo.bookmarkAdded(newIterator.next(), false)
      }
    }
    return true
  }

  private fun contains(group: Group) = allGroups.contains(group)
  private fun contains(group: BookmarkGroup) = group is Group && contains(group)

  private fun canDragInto(group: BookmarkGroup, occurrence: BookmarkOccurrence): Boolean {
    val to = group as? Group ?: return false
    val from = occurrence.group as? Group ?: return false
    val info = allBookmarks[occurrence.bookmark] ?: return groupLineBookmarks // fake file node
    return info.groups.contains(from)
  }

  @ApiStatus.Internal
  fun canDragInto(group: BookmarkGroup, occurrences: List<BookmarkOccurrence>): Boolean = synchronized(notifier) {
    contains(group) && occurrences.all { canDragInto(group, it) }
  }

  @ApiStatus.Internal
  fun dragInto(group: BookmarkGroup, occurrences: List<BookmarkOccurrence>): Boolean = synchronized(notifier) {
    canDragInto(group, occurrences) && drag(group as Group, occurrences) { 0 }
  }

  @ApiStatus.Internal
  fun canDrag(above: Boolean, occurrence: BookmarkOccurrence, occurrences: List<BookmarkOccurrence>): Boolean = synchronized(notifier) {
    if (!contains(occurrence.group)) return false
    if (!occurrences.all { it != occurrence && canDragInto(occurrence.group, it) }) return false
    if (!groupLineBookmarks) return true
    if (!above) {
      val file = getFileGrouping(occurrence.bookmark)
      if (file != null && occurrences.all { isLineGrouping(it.bookmark, file) }) return true
    }
    val file = getLineGrouping(occurrence.bookmark)
    if (file != null) return occurrences.all { isLineGrouping(it.bookmark, file) }
    return occurrences.all { it.bookmark !is LineBookmarkImpl }
  }

  @ApiStatus.Internal
  fun drag(above: Boolean, occurrence: BookmarkOccurrence, occurrences: List<BookmarkOccurrence>): Boolean = synchronized(notifier) {
    canDrag(above, occurrence, occurrences) && drag(occurrence.group as Group, occurrences) {
      val index = it.indexWithGrouping(occurrence.bookmark)
      if (index < 0 || above) index else index + 1
    }
  }

  @ApiStatus.Internal
  fun canDrag(above: Boolean, group: BookmarkGroup, groups: List<BookmarkGroup>): Boolean = synchronized(notifier) {
    (!above || group != defaultGroup) && contains(group) && groups.all {
      it != defaultGroup && it != group && contains(it) && getParentGroup(it) === getParentGroup(group)
    }
  }

  @ApiStatus.Internal
  fun drag(above: Boolean, group: BookmarkGroup, groups: List<BookmarkGroup>): Boolean = synchronized(notifier) {
    if (!canDrag(above, group, groups)) return false
    val set = mutableSetOf<Group>()
    groups.mapTo(set) { it as Group }
    if (groups.size != set.size) return false
    allGroups.removeAll(set)
    set.forEach { notifier.groupRemoved(it) }
    var index = allGroups.indexOf(group as Group).coerceAtLeast(0) // see #canDrag(Boolean, BookmarkGroup, List<BookmarkGroup>)
    if (!above) index++
    allGroups.addAll(index, set)
    set.forEach { notifier.groupAdded(it) }
    return true
  }

  @ApiStatus.Internal
  fun canDragAddInto(group: BookmarkGroup, files: List<File>): Boolean = synchronized(notifier) {
    if (!contains(group)) return false
    val set = files.mapTo(mutableSetOf()) { it.systemIndependentPath }
    return !group.getBookmarks().any { it is FileBookmarkImpl && set.contains(it.file.path) }
  }

  @ApiStatus.Internal
  fun dragAddInto(group: BookmarkGroup, files: List<File>): Boolean = synchronized(notifier) {
    canDragAddInto(group, files) && dragAdd(group as Group, files) { 0 }
  }

  @ApiStatus.Internal
  fun dragAdd(above: Boolean, occurrence: BookmarkOccurrence, files: List<File>): Boolean = synchronized(notifier) {
    canDragAddInto(occurrence.group, files) && dragAdd(occurrence.group as Group, files) {
      val index = it.indexWithGrouping(occurrence.bookmark)
      if (index < 0 || above) index else index + 1
    }
  }

  private fun dragAdd(group: Group, files: List<File>, indexSupplier: (Group) -> Int): Boolean {
    val provider = LineBookmarkProvider.Util.find(project) ?: return false
    val bookmarks = files.mapNotNull { provider.createBookmark(VfsUtil.findFileByIoFile(it, true)) }.ifEmpty { return false }
    val index = indexSupplier(group).coerceAtLeast(0)
    bookmarks.forEach { group.add(it, BookmarkType.DEFAULT, null, index) }
    return true
  }

  private fun drag(group: Group, occurrences: List<BookmarkOccurrence>, indexSupplier: (Group) -> Int): Boolean {
    val pairs = mutableListOf<Pair<InManagerInfo, InGroupInfo?>>()
    occurrences.forEach { (it.group as Group).removeWithGrouping(it.bookmark) { pair -> pairs.add(0, pair) } }
    val index = indexSupplier(group).coerceAtLeast(0)
    pairs.forEach { group.add(it.first.bookmark, it.first.type, it.first.description ?: it.second?.description, index) }
    return true
  }

  // see file-line grouping in LineBookmarkProvider.prepareGroup
  private fun getFileGrouping(bookmark: Bookmark) = if (bookmark is FileBookmarkImpl) bookmark.file else null
  private fun getLineGrouping(bookmark: Bookmark) = if (bookmark is LineBookmarkImpl) bookmark.file else null
  private fun isLineGrouping(bookmark: Bookmark, file: VirtualFile) = bookmark is LineBookmarkImpl && bookmark.file == file

  @ApiStatus.Internal
  internal inner class Group(name: String, val id: String = UUID.randomUUID().toString(), var parentId: String? = null) : BookmarkGroup {
    internal constructor(name: String, isDefault: Boolean, asFirst: Boolean, id: String = UUID.randomUUID().toString(), parentId: String? = null) : this(name, id, parentId) {
      val index = when {
        isDefault -> 0
        !asFirst -> allGroups.size
        defaultGroup == null -> 0
        else -> allGroups.size.coerceAtMost(1)
      }
      addGroupTo(index, this)
      this.isDefault = isDefault
    }

    internal var hash: Int = name.hashCode()
    override var name: String = name
      set(value) = synchronized(notifier) { // do not allow to use an empty name or the name of an existing group
        if (value.isNotBlank() && value != field && allGroups.none { it !== this && it.parentId == parentId && it.name == value }) {
          field = value
          hash = value.hashCode() // notify only if the group is added
          if (contains(this)) notifier.groupRenamed(this)
        }
      }

    override var isDefault: Boolean
      get() = this === defaultGroup
      set(value) = synchronized(notifier) {
        when {
          value -> defaultGroup = this
          isDefault -> defaultGroup = null
        }
      }

    private val groupBookmarks = mutableListOf<InGroupInfo>()

    internal fun indexOf(bookmark: Bookmark): Int = when (groupBookmarks.size) {
      0 -> -1
      1 -> if (groupBookmarks[0].bookmark == bookmark) 0 else -1
      else -> {
        val hash = bookmark.hashCode()
        groupBookmarks.indexOfFirst { it.hash == hash && it.bookmark == bookmark }
      }
    }

    internal fun indexWithGrouping(bookmark: Bookmark): Int {
      val index = indexOf(bookmark)
      if (index >= 0) return index
      if (!groupLineBookmarks) return -1
      val file = getFileGrouping(bookmark) ?: return -1
      return groupBookmarks.indexOfFirst { isLineGrouping(it.bookmark, file) }
    }

    internal fun removeWithGrouping(bookmark: Bookmark, process: (Pair<InManagerInfo, InGroupInfo?>) -> Unit) {
      removeFromGroup(this, bookmark)?.run { process(this) }
      if (!groupLineBookmarks) return
      val file = getFileGrouping(bookmark) ?: return
      val bookmarks = groupBookmarks.mapNotNull { if (isLineGrouping(it.bookmark, file)) it.bookmark else null }
      bookmarks.forEach { removeFromGroup(this, it)?.run { process(this) } }
    }

    private fun getInfo(bookmark: Bookmark) = indexOf(bookmark).let { if (it < 0) null else groupBookmarks[it] }

    internal fun removeInfo(bookmark: Bookmark): InGroupInfo? = indexOf(bookmark).let { if (it < 0) null else groupBookmarks.removeAt(it) }

    internal fun updateInfo(old: Bookmark, new: Bookmark): Boolean {
      val index = indexOf(old)
      if (index < 0) return false
      val info = groupBookmarks[index]
      val description = if (info.isCustomDescription) info.description else null
      groupBookmarks[index] = InGroupInfo(new, description, info.isCustomDescription)
      return true
    }

    override fun getBookmarks(): List<Bookmark> = synchronized(notifier) { groupBookmarks.map { it.bookmark } }

    override fun getDescription(bookmark: Bookmark): String? = synchronized(notifier) {
      val info = getInfo(bookmark) ?: return null
      allBookmarks[bookmark]?.description?.let { return it }
      // create description on first request if it is not initialized
      if (info.description == null) info.description = createDescription(bookmark)
      info.description
    }

    override fun setDescription(bookmark: Bookmark, description: String): Unit = synchronized(notifier) {
      if (getInfo(bookmark) == null) return
      val shared = allBookmarks[bookmark] ?: return
      if (shared.description == description) return
      shared.description = description
      shared.restoredDescriptions.clear()
      shared.groups.forEach { group ->
        group.getInfo(bookmark)?.apply { this.description = description; isCustomDescription = true }
        notifier.bookmarkChanged(group, bookmark)
      }
      shared.refreshRenderer()
    }

    override fun canAdd(bookmark: Bookmark): Boolean = synchronized(notifier) {
      contains(this) && indexOf(bookmark) < 0
    }

    override fun add(bookmark: Bookmark, type: BookmarkType, description: String?): Boolean {
      if (!canRewriteType(type, bookmark)) return false
      if (!add(bookmark, type, description, 0)) return false
      notifier.selectLater { it.select(this, bookmark) }
      return true
    }

    internal fun add(bookmark: Bookmark, type: BookmarkType, description: String?, index: Int, restoring: Boolean = false): Boolean = synchronized(notifier) {
      if (!canAdd(bookmark)) return false // bookmark is already exist
      rewriteType(type, bookmark)
      val info = allBookmarks.computeIfAbsent(bookmark) { InManagerInfo(it, type) }
      if (restoring && description != null) {
        // Earlier versions allowed different descriptions for a file in different groups.
        // Preserve every distinct legacy text when migrating to a shared description.
        info.restoredDescriptions[id] = description
        val distinct = info.restoredDescriptions.entries.distinctBy { it.value }
        info.description = if (distinct.size == 1) distinct[0].value else distinct.joinToString("\n\n---\n\n") {
          "## 原分组：${getGroupById(it.key)?.let { group -> getGroupPath(group) } ?: it.key}\n\n${it.value}"
        }
      }
      else if (info.description == null && description != null) info.description = description
      groupBookmarks.add(if (index < 0) groupBookmarks.size else index, InGroupInfo(info.bookmark, description, description != null))
      val added = info.groups.isEmpty()
      info.groups.add(this)
      info.bookmarkAdded(this, added)
      if (restoring) info.groups.forEach { notifier.bookmarkChanged(it, info.bookmark) }
      info.changeType(type)
      return true
    }

    /**
     * Creates a bookmark from the specified context and adds it to the group if possible.
     * It is intended to restore bookmark state or to migrate old bookmarks and favorites.
     * Each bookmark is created separately that allows to wait for the end of indexing.
     */
    internal fun addLater(context: Any, type: BookmarkType, description: String?, restoredBookmarks: MutableMap<String, Bookmark?>? = null) {
      val generation = restoreGeneration
      pendingRestores.incrementAndGet()
      invoker.invokeLater {
        try {
          val bookmark = if (generation != restoreGeneration) null
          else if (restoredBookmarks != null && context is BookmarkState && context.id != null) synchronized(restoredBookmarks) {
            val id = context.id!!
            if (restoredBookmarks.containsKey(id)) restoredBookmarks[id]
            else createBookmark(context).also { restoredBookmarks[id] = it }
          }
          else createBookmark(context)
          synchronized(notifier) {
            if (generation == restoreGeneration) {
              when (bookmark?.let { add(it, type, description, -1, true) }) {
                null -> {
                  if (context is BookmarkState) {
                    val existing = unresolvedBookmarks.values.asSequence().flatten().firstOrNull {
                      it.provider == context.provider && it.attributes == context.attributes
                    }
                    context.id = existing?.id ?: context.id ?: UUID.randomUUID().toString()
                    unresolvedBookmarks.getOrPut(id) { mutableListOf() }.add(context)
                  }
                  notifier.groupsSorted()
                  LOG.info("cannot create bookmark for $context; retained in storage")
                }
                false -> LOG.info("cannot add bookmark for $context")
                else -> if (context is BookmarkState && context.id != null) allBookmarks[bookmark]?.id = context.id!!
              }
            }
          }
        }
        finally {
          synchronized(notifier) {
            if (pendingRestores.decrementAndGet() == 0) notifier.groupsSorted()
          }
        }
      }
    }

    override fun canRemove(bookmark: Bookmark): Boolean = synchronized(notifier) {
      allBookmarks[bookmark]?.groups?.contains(this) ?: false
    }

    override fun remove(bookmark: Bookmark): Boolean = removeFromGroup(this, bookmark) != null

    override fun remove(): Unit = synchronized(notifier) {
      getChildGroups(this).forEach { it.remove() }
      val index = allGroups.indexOf(this)
      if (index >= 0) {
        unresolvedBookmarks.remove(id)
        if (isDefault) defaultGroup = null
        for (bookmark in getBookmarks()) {
          removeFromGroup(this, bookmark)
        }
        removeGroupFrom(index)
      }
    }

    internal fun sortLater(): CancellablePromise<*> = invoker.invokeLater { sort() }

    private fun sort(): Unit = synchronized(notifier) {
      if (groupBookmarks.isEmpty()) return
      if (!contains(this)) return
      val list = groupBookmarks.sortedWith(this::compare)
      if (list == groupBookmarks) return
      groupBookmarks.clear()
      groupBookmarks.addAll(list)
      notifier.bookmarksSorted(this)
    }

    private fun compare(info1: InGroupInfo, info2: InGroupInfo): Int {
      val weight1 = info1.bookmark.provider.weight
      val weight2 = info2.bookmark.provider.weight
      if (weight1 > weight2) return -1
      if (weight1 < weight2) return 1
      return info1.bookmark.provider.compare(info1.bookmark, info2.bookmark)
    }

    internal fun move(bookmark: Bookmark, anchor: Bookmark): Unit = synchronized(notifier) {
      val fromIndex = indexWithGrouping(bookmark)
      if (fromIndex < 0) return // first bookmark does not exist
      val toIndex = indexWithGrouping(anchor)
      if (toIndex < 0 || toIndex == fromIndex) return // second bookmark does not exist or equal the first one
      val info = groupBookmarks.removeAt(fromIndex.coerceAtLeast(toIndex))
      notifier.bookmarkRemoved(this, info.bookmark)
      groupBookmarks.add(toIndex.coerceAtMost(fromIndex), info)
      notifier.bookmarkAdded(this, info.bookmark)
    }

    internal fun getState(): GroupState = GroupState().also {
      it.id = id
      it.parentId = parentId
      it.name = name
      it.isDefault = isDefault
      for (info in groupBookmarks) {
        it.bookmarks.add(info.getState())
      }
      it.bookmarks.addAll(unresolvedBookmarks[id].orEmpty())
    }
  }


  internal inner class InGroupInfo(val bookmark: Bookmark, var description: String?, var isCustomDescription: Boolean = false) {
    val hash: Int = bookmark.hashCode()

    fun getState(): BookmarkState = BookmarkState().also {
      it.id = allBookmarks[bookmark]?.id
      it.provider = bookmark.provider.javaClass.name
      it.description = allBookmarks[bookmark]?.description ?: if (isCustomDescription) description else null
      it.type = allBookmarks[bookmark]?.type ?: BookmarkType.DEFAULT
      it.attributes.putAll(bookmark.attributes)
    }
  }


  internal fun findLineHighlighter(bookmark: Bookmark): RangeHighlighter? = synchronized(notifier) {
    allBookmarks[bookmark]?.renderer?.highlighter
  }

  internal fun refreshRenderers(file: VirtualFile): Unit = synchronized(notifier) {
    allBookmarks.values.forEach { if (it.renderer?.bookmark?.file == file) it.refreshRenderer() }
  }

  internal inner class InManagerInfo(val bookmark: Bookmark, var type: BookmarkType) {
    var id: String = UUID.randomUUID().toString()
    var description: String? = null
    val restoredDescriptions: MutableMap<String, String> = linkedMapOf()
    val renderer: GutterLineBookmarkRenderer? = (bookmark as? LineBookmark)?.let { GutterLineBookmarkRenderer(it) }
    val groups: MutableSet<Group> = mutableSetOf()

    fun refreshRenderer(): Unit? = renderer?.refreshHighlighter { groups.isEmpty() }

    fun bookmarkAdded(group: BookmarkGroup, initial: Boolean) {
      notifier.bookmarkAdded(group, bookmark)
      if (initial) refreshRenderer()
    }

    fun bookmarkRemoved(group: BookmarkGroup, completely: Boolean) {
      notifier.bookmarkRemoved(group, bookmark)
      if (completely) refreshRenderer()
    }

    fun changeType(type: BookmarkType) {
      if (this.type == type) return
      this.type = type
      notifier.bookmarkTypeChanged(bookmark)
      refreshRenderer()
    }
  }
}
