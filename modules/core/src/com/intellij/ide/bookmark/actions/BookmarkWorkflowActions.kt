package com.intellij.ide.bookmark.actions

import com.intellij.ide.bookmark.BookmarksManagerImpl
import com.intellij.ide.bookmark.ui.BookmarkHealthDialog
import com.intellij.ide.bookmark.ui.BookmarkReadingDialog
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

class InspectBookmarksAction : DumbAwareAction("失效书签巡检与修复") {
  override fun getActionUpdateThread() = ActionUpdateThread.BGT
  override fun update(event: AnActionEvent) {
    event.presentation.isEnabled = (event.bookmarksManager as? BookmarksManagerImpl)?.isRestoringBookmarks() == false
  }
  override fun actionPerformed(event: AnActionEvent) {
    val project = event.project ?: return
    val manager = event.bookmarksManager as? BookmarksManagerImpl ?: return
    BookmarkHealthDialog(project, manager).show()
  }
}

class ReadBookmarkRouteAction : DumbAwareAction("代码阅读路线") {
  override fun getActionUpdateThread() = ActionUpdateThread.BGT
  override fun update(event: AnActionEvent) {
    val manager = event.bookmarksManager as? BookmarksManagerImpl
    event.presentation.isEnabled = manager != null && !manager.isRestoringBookmarks() && manager.groups.isNotEmpty()
  }
  override fun actionPerformed(event: AnActionEvent) {
    val project = event.project ?: return
    val manager = event.bookmarksManager as? BookmarksManagerImpl ?: return
    BookmarkReadingDialog.open(project, manager, event.selectedGroupNode?.value?.let { manager.getGroupId(it) })
  }
}
