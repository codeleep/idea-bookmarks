package com.intellij.ide.bookmark.actions

import com.intellij.ide.bookmark.BookmarksManagerImpl
import com.intellij.ide.bookmark.ui.BookmarkHealthDialog
import com.intellij.ide.bookmark.ui.BookmarkWorkbenchFactory
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
    BookmarkWorkbenchFactory.open(project, 0, event.selectedGroupNode?.value?.let { manager.getGroupId(it) })
  }
}

class ShowBookmarkImpactAction : DumbAwareAction("源码变更影响提醒") {
  override fun actionPerformed(event: AnActionEvent) { event.project?.let { BookmarkWorkbenchFactory.open(it, 1) } }
}

class ShowBookmarkRelationsAction : DumbAwareAction("书签关联") {
  override fun actionPerformed(event: AnActionEvent) {
    val project = event.project ?: return
    val manager = event.bookmarksManager as? BookmarksManagerImpl ?: return
    val bookmark = (event.bookmarkNodes?.singleOrNull() as? com.intellij.ide.bookmark.ui.tree.BookmarkNode<*>)?.value ?: event.contextBookmark
    BookmarkWorkbenchFactory.open(project, 2, bookmarkId = bookmark?.let { manager.getBookmarkId(it) })
  }
}

class CopyBookmarkLinkAction : DumbAwareAction("复制书签链接") {
  override fun actionPerformed(event: AnActionEvent) {
    val manager = event.bookmarksManager as? BookmarksManagerImpl ?: return
    val bookmark = (event.bookmarkNodes?.singleOrNull() as? com.intellij.ide.bookmark.ui.tree.BookmarkNode<*>)?.value ?: event.contextBookmark ?: return
    val id = manager.getBookmarkId(bookmark) ?: return
    com.intellij.openapi.ide.CopyPasteManager.getInstance().setContents(java.awt.datatransfer.StringSelection(com.intellij.ide.bookmark.BookmarkLinks.url(id)))
  }
}
