package com.intellij.ide.bookmark.actions

import com.intellij.ide.bookmark.Bookmark
import com.intellij.ide.bookmark.BookmarksDevBundle.message
import com.intellij.ide.bookmark.BookmarksManagerImpl
import com.intellij.ide.bookmark.ui.BookmarkGroupsDialog
import com.intellij.ide.bookmark.ui.tree.BookmarkNode
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

internal class ManageBookmarkGroupsAction : DumbAwareAction(message("bookmark.groups.action")) {
  private fun bookmark(event: AnActionEvent): Bookmark? =
    if (event.contextBookmarks != null) null
    else (event.bookmarkNodes?.singleOrNull() as? BookmarkNode<*>)?.value ?: event.contextBookmark
  override fun getActionUpdateThread() = ActionUpdateThread.BGT
  override fun update(event: AnActionEvent) {
    event.presentation.isEnabledAndVisible = bookmark(event) != null && event.bookmarksManager?.groups?.isNotEmpty() == true
  }
  override fun actionPerformed(event: AnActionEvent) {
    val project = event.project ?: return
    val manager = event.bookmarksManager as? BookmarksManagerImpl ?: return
    val bookmark = bookmark(event) ?: return
    BookmarkGroupsDialog(project, manager, bookmark).showAndUpdate()
  }
}
