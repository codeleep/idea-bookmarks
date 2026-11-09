package com.intellij.ide.bookmark.ui

import com.intellij.ide.bookmark.BookmarksManager
import com.intellij.ide.bookmark.BookmarksManagerImpl
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.JTabbedPane

class BookmarkWorkbenchFactory : ToolWindowFactory, DumbAware {
  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val manager = BookmarksManager.getInstance(project) as? BookmarksManagerImpl ?: return
    val panel = BookmarkWorkbenchPanel(project, manager)
    toolWindow.contentManager.addContent(toolWindow.contentManager.factory.createContent(panel, null, false).apply { setDisposer(panel) })
  }

  companion object {
    const val ID = "书签阅读工作台"
    @JvmStatic fun open(project: Project, tab: Int, groupId: String? = null, bookmarkId: String? = null) {
      val window = ToolWindowManager.getInstance(project).getToolWindow(ID) ?: return
      window.activate { (window.contentManager.selectedContent?.component as? BookmarkWorkbenchPanel)?.select(tab, groupId, bookmarkId) }
    }
  }
}

class BookmarkWorkbenchPanel(project: Project, manager: BookmarksManagerImpl) : JPanel(BorderLayout()), Disposable {
  private val tabs = JTabbedPane()
  val reader = BookmarkReadingPanel(project, manager, null)
  val relations = BookmarkRelationsPanel(project, manager)
  val impact = BookmarkImpactPanel(project, manager) { entries ->
    reader.updateImpacts(entries.associateBy { it.id })
    val count = entries.count { it.status in setOf(com.intellij.ide.bookmark.BookmarkImpact.Status.CHANGED, com.intellij.ide.bookmark.BookmarkImpact.Status.MOVED) }
    if (tabs.tabCount >= 2) tabs.setTitleAt(1, if (count == 0) "变更影响" else "变更影响（$count 待复核）")
  }
  init {
    tabs.addTab("阅读路线", reader); tabs.addTab("变更影响", impact); tabs.addTab("书签关联", relations)
    add(tabs, BorderLayout.CENTER)
    Disposer.register(this, reader); Disposer.register(this, impact); Disposer.register(this, relations)
  }
  fun select(tab: Int, groupId: String?, bookmarkId: String?) {
    tabs.selectedIndex = tab.coerceIn(0, 2)
    when (tabs.selectedIndex) {
      0 -> reader.selectGroup(groupId)
      1 -> impact.selectBookmark(bookmarkId)
      2 -> relations.selectBookmark(bookmarkId)
    }
  }
  override fun dispose() = Unit
}
