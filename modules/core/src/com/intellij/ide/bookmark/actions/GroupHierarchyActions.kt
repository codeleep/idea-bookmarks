package com.intellij.ide.bookmark.actions

import com.intellij.ide.bookmark.BookmarksDevBundle.message
import com.intellij.ide.bookmark.BookmarksManagerImpl
import com.intellij.ide.bookmark.ui.GroupCreateDialog
import com.intellij.ide.bookmark.ui.GroupParentDialog
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

internal class CreateSubgroupAction : DumbAwareAction(message("group.child.action")) {
  override fun getActionUpdateThread() = ActionUpdateThread.BGT
  override fun update(event: AnActionEvent) {
    event.presentation.isEnabledAndVisible = event.selectedGroupNode != null
  }
  override fun actionPerformed(event: AnActionEvent) {
    val manager = event.bookmarksManager ?: return
    val view = event.bookmarksView ?: return
    val parent = event.selectedGroupNode?.value ?: return
    GroupCreateDialog(event.project, view, manager, parent).showAndGetGroup(false)
  }
}

internal class MoveGroupAction : DumbAwareAction(message("group.move.action")) {
  override fun getActionUpdateThread() = ActionUpdateThread.BGT
  override fun update(event: AnActionEvent) {
    event.presentation.isEnabledAndVisible = event.selectedGroupNode != null
  }
  override fun actionPerformed(event: AnActionEvent) {
    val manager = event.bookmarksManager as? BookmarksManagerImpl ?: return
    val view = event.bookmarksView ?: return
    val group = event.selectedGroupNode?.value ?: return
    GroupParentDialog(event.project, view, manager, group).showAndMove()
  }
}
