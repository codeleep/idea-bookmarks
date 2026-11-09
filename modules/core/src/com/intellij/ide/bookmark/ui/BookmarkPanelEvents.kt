package com.intellij.ide.bookmark.ui

import com.intellij.ide.bookmark.*
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project

internal fun listenToBookmarks(project: Project, disposable: Disposable, changed: () -> Unit) {
  project.messageBus.connect(disposable).subscribe(BookmarksListener.TOPIC, object : BookmarksListener {
    override fun groupsSorted() = changed()
    override fun groupAdded(group: BookmarkGroup) = changed()
    override fun groupRemoved(group: BookmarkGroup) = changed()
    override fun groupRenamed(group: BookmarkGroup) = changed()
    override fun bookmarksSorted(group: BookmarkGroup) = changed()
    override fun bookmarkAdded(group: BookmarkGroup, bookmark: Bookmark) = changed()
    override fun bookmarkRemoved(group: BookmarkGroup, bookmark: Bookmark) = changed()
    override fun bookmarkChanged(group: BookmarkGroup, bookmark: Bookmark) = changed()
    override fun bookmarkTypeChanged(bookmark: Bookmark) = changed()
    override fun defaultGroupChanged(oldGroup: BookmarkGroup?, newGroup: BookmarkGroup?) = changed()
    override fun structureChanged(node: Any?) = changed()
  })
}
