package com.intellij.platform.debugger.impl.bookmarks

import com.intellij.ide.bookmark.BookmarksDevBundle.message
import com.intellij.ide.bookmark.ui.BookmarksView
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.xdebugger.impl.breakpoints.ui.BreakpointItem

abstract class SetBreakpointsEnabledAction(private val enabled: Boolean) :
  DumbAwareAction(message(if (enabled) "breakpoints.enable" else "breakpoints.disable")) {

  override fun getActionUpdateThread() = ActionUpdateThread.BGT

  private fun selectedItems(event: AnActionEvent): List<BreakpointItem>? {
    val project = event.project ?: return null
    val view = event.getData(BookmarksView.BOOKMARKS_VIEW) ?: return null
    if (view.project !== project) return null
    return BreakpointListProvider.selectedItems(project, event.getData(PlatformDataKeys.SELECTED_ITEMS))
  }

  override fun update(event: AnActionEvent) {
    val items = selectedItems(event)
    event.presentation.isVisible = items != null
    event.presentation.isEnabled = items?.any { it.isEnabled != enabled } == true
  }

  override fun actionPerformed(event: AnActionEvent) {
    val items = selectedItems(event) ?: return
    WriteAction.run<RuntimeException> {
      items.filter { it.isEnabled != enabled }.forEach { it.isEnabled = enabled }
    }
  }
}

class EnableBreakpointsAction : SetBreakpointsEnabledAction(true)
class DisableBreakpointsAction : SetBreakpointsEnabledAction(false)
