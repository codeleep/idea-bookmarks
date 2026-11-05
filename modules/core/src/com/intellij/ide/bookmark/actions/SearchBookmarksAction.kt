package com.intellij.ide.bookmark.actions

import com.intellij.ide.bookmark.BookmarksDevBundle.message
import com.intellij.ide.bookmark.BookmarksManagerImpl
import com.intellij.ide.bookmark.ui.BookmarkSearchDialog
import com.intellij.ide.bookmark.ui.BookmarksView
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager

class SearchBookmarksAction : DumbAwareAction(message("search.title")) {
  override fun getActionUpdateThread() = ActionUpdateThread.BGT
  override fun update(event: AnActionEvent) {
    event.presentation.isEnabled = (event.bookmarksManager as? BookmarksManagerImpl)?.isRestoringBookmarks() == false
  }

  override fun actionPerformed(event: AnActionEvent) {
    val project = event.project ?: return
    val manager = event.bookmarksManager as? BookmarksManagerImpl ?: return
    BookmarkSearchDialog(project, manager) { entry, query ->
      val bookmark = manager.bookmarks.firstOrNull { manager.getBookmarkId(it) == entry.id } ?: return@BookmarkSearchDialog
      val preferred = entry.preferredGroup(query)?.id
      val groups = manager.getGroups(bookmark)
      val group = groups.firstOrNull { manager.getGroupId(it) == preferred } ?: groups.firstOrNull() ?: return@BookmarkSearchDialog
      fun locate(view: BookmarksView?) {
        if (view == null) { if (bookmark.canNavigate()) bookmark.navigate(true) }
        else view.select(group, bookmark).onSuccess { if (bookmark.canNavigate()) bookmark.navigate(true) }
      }
      val window = ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId.BOOKMARKS)
      if (window == null) locate(event.bookmarksView)
      else window.activate { locate(window.contentManager.selectedContent?.component as? BookmarksView) }
    }.show()
  }
}
