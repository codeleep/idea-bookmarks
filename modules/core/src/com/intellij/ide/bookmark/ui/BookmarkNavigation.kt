package com.intellij.ide.bookmark.ui

import com.intellij.ide.bookmark.BookmarksManagerImpl
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager

object BookmarkNavigation {
  @JvmStatic fun locate(project: Project, manager: BookmarksManagerImpl, id: String, groupId: String? = null): Boolean {
    if (manager.isRestoringBookmarks()) return false
    val bookmark = manager.bookmarks.firstOrNull { manager.getBookmarkId(it) == id } ?: return false
    if (!bookmark.canNavigate()) return false
    val groups = manager.getGroups(bookmark)
    val group = groups.firstOrNull { manager.getGroupId(it) == groupId } ?: groups.firstOrNull() ?: return false
    fun navigate(view: BookmarksView?) {
      if (project.isDisposed || bookmark !in manager.bookmarks) return
      if (view == null) bookmark.navigate(true)
      else view.select(group, bookmark).onSuccess { if (bookmark in manager.bookmarks && bookmark.canNavigate()) bookmark.navigate(true) }
    }
    val window = ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId.BOOKMARKS)
    if (window == null) navigate(null)
    else window.activate { navigate(window.contentManager.selectedContent?.component as? BookmarksView) }
    return true
  }
}
