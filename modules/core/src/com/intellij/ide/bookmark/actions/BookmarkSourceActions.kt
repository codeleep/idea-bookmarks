package com.intellij.ide.bookmark.actions

import com.intellij.ide.bookmark.*
import com.intellij.ide.bookmark.ui.*
import com.intellij.ide.bookmark.ui.tree.BookmarkNode
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import javax.swing.JMenu
import javax.swing.JMenuItem

class BookmarkSourceActionsGroup : ActionGroup("源码分析", true), DumbAware {
  override fun getChildren(event: AnActionEvent?): Array<AnAction> = BookmarkSourceMenus.actions()
}

object BookmarkSourceMenus {
  private fun selectedId(event: AnActionEvent, manager: BookmarksManagerImpl): String? {
    val bookmark = (event.bookmarkNodes?.singleOrNull() as? BookmarkNode<*>)?.value
    return bookmark?.let { manager.getBookmarkId(it) } ?: event.getData(BookmarkReadingPanel.BOOKMARK_ID)
  }
  /** Diagram support is optional and reached through the native action registry only. */
  @JvmStatic fun installNativeMenus() {
    val actions = ActionManager.getInstance()
    val add = actions.getAction("Bookmarks.AddSourceResults") ?: return
    for (id in listOf("UML.DefaultGraphPopup", "UsageView.Popup", "HierarchyPopupMenu.Common")) {
      val group = actions.getAction(id) as? DefaultActionGroup ?: continue
      if (add !in group.getChildActionsOrStubs()) group.add(add)
    }
  }
  private fun available(project: Project, bookmark: FileBookmark?, operation: BookmarkSourceNavigation.Operation): Boolean {
    val actions = ActionManager.getInstance()
    if (actions.getAction(operation.actionId) == null) return false
    // ShowUmlDiagram can exist while its Java/Kotlin provider module is unavailable.
    return operation != BookmarkSourceNavigation.Operation.DIAGRAM || bookmark?.file?.extension !in setOf("java", "kt") ||
      actions.getAction("NewJavaDiagram") != null
  }
  @JvmStatic fun actions(): Array<AnAction> = BookmarkSourceNavigation.Operation.entries.map { operation ->
    object : DumbAwareAction(operation.label) {
      override fun getActionUpdateThread() = ActionUpdateThread.BGT
      override fun update(event: AnActionEvent) {
        val manager = event.bookmarksManager as? BookmarksManagerImpl
        val id = manager?.let { selectedId(event, it) }
        val bookmark = manager?.bookmarks?.firstOrNull { manager.getBookmarkId(it) == id }
        event.presentation.isEnabled = event.project?.let { !DumbService.isDumb(it) } == true &&
          manager?.isRestoringBookmarks() == false && bookmark is FileBookmark && bookmark.canNavigate() &&
          !bookmark.file.isDirectory && available(event.project!!, bookmark, operation)
        if (operation == BookmarkSourceNavigation.Operation.DIAGRAM) event.presentation.description = "需要宿主 IDEA 已启用对应语言的原生类图功能"
      }
      override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val manager = event.bookmarksManager as? BookmarksManagerImpl ?: return
        selectedId(event, manager)?.let { invoke(project, it, operation) }
      }
    }
  }.toTypedArray()

  @JvmStatic fun menu(project: Project, id: String?) = JMenu("源码分析").apply {
    isEnabled = id != null && !DumbService.isDumb(project)
    BookmarkSourceNavigation.Operation.entries.forEach { operation ->
      add(JMenuItem(operation.label).apply {
        val manager = BookmarksManager.getInstance(project) as? BookmarksManagerImpl
        val bookmark = manager?.bookmarks?.firstOrNull { manager.getBookmarkId(it) == id } as? FileBookmark
        isEnabled = bookmark != null && bookmark.canNavigate() && available(project, bookmark, operation)
        if (operation == BookmarkSourceNavigation.Operation.DIAGRAM) toolTipText = "需要宿主 IDEA 已启用对应语言的原生类图功能"
        addActionListener { if (id != null) invoke(project, id, operation) }
      })
    }
  }

  @JvmStatic fun invoke(project: Project, id: String, operation: BookmarkSourceNavigation.Operation) {
    val manager = BookmarksManager.getInstance(project) as? BookmarksManagerImpl ?: return
    if (DumbService.isDumb(project) || manager.isRestoringBookmarks()) {
      Messages.showInfoMessage(project, "索引或书签正在恢复，请稍后重试。", "源码分析"); return
    }
    val bookmark = manager.bookmarks.firstOrNull { manager.getBookmarkId(it) == id } as? FileBookmark
    if (!available(project, bookmark, operation)) {
      Messages.showInfoMessage(project, "宿主 IDEA 尚未启用这项原生功能。Java／Kotlin 类图需要对应的 Diagrams 模块及可用授权。", "源码分析"); return
    }
    val symbols = BookmarkSourceNavigation.bookmarkSymbols(project, manager, id)
    val revision = manager.stateModificationCount
    val action = ActionManager.getInstance().getAction(operation.actionId)
    if (symbols.isEmpty() || action == null) {
      Messages.showInfoMessage(project, "该行没有可分析的源码符号，或对应原生功能未启用。请选择类、方法或具体使用位置。", "源码分析"); return
    }
    fun invoke(symbol: BookmarkSourceNavigation.Symbol) {
      val editor = symbol.context.getData(CommonDataKeys.EDITOR) ?: return
      if (editor.isDisposed || editor.document.modificationStamp != symbol.stamp || manager.isRestoringBookmarks() || revision != manager.stateModificationCount) {
        Messages.showInfoMessage(project, "源码或书签上下文已变化，请重新分析。", "源码分析"); return
      }
      editor.caretModel.moveToOffset(symbol.offset)
      ActionUtil.invokeAction(action, symbol.context, "Bookmarks.SourceAnalysis", null, null)
    }
    if (symbols.size == 1) invoke(symbols.single())
    else com.intellij.openapi.ui.popup.JBPopupFactory.getInstance().createPopupChooserBuilder(symbols)
      .setTitle("选择本行要分析的符号").setItemChosenCallback { invoke(it) }.createPopup().showInFocusCenter()
  }
}

class EditBookmarkRouteNoteAction : DumbAwareAction("编辑本站说明…") {
  override fun getActionUpdateThread() = ActionUpdateThread.BGT
  override fun update(event: AnActionEvent) {
    event.presentation.isEnabled = event.getData(BookmarkReadingPanel.PANEL) != null &&
      event.getData(BookmarkReadingPanel.BOOKMARK_ID) != null && (event.bookmarksManager as? BookmarksManagerImpl)?.isRestoringBookmarks() == false
  }
  override fun actionPerformed(event: AnActionEvent) { event.getData(BookmarkReadingPanel.PANEL)?.editCurrentNote() }
}

/** The same action is attached to the native Find and hierarchy result menus. */
class AddSourceResultsToBookmarksAction : DumbAwareAction("加入书签／阅读路线…") {
  override fun getActionUpdateThread() = ActionUpdateThread.BGT
  override fun update(event: AnActionEvent) {
    val project = event.project
    event.presentation.isEnabledAndVisible = project != null && !DumbService.isDumb(project) &&
      (BookmarksManager.getInstance(project) as? BookmarksManagerImpl)?.isRestoringBookmarks() == false &&
      (event.getData(com.intellij.usages.UsageView.USAGES_KEY) != null || event.getData(LangDataKeys.PSI_ELEMENT_ARRAY) != null ||
        event.getData(PlatformDataKeys.SELECTED_ITEMS) != null || event.getData(CommonDataKeys.NAVIGATABLE_ARRAY) != null ||
        event.getData(CommonDataKeys.PSI_ELEMENT) != null)
  }
  override fun actionPerformed(event: AnActionEvent) {
    val project = event.project ?: return
    val manager = BookmarksManager.getInstance(project) as? BookmarksManagerImpl ?: return
    try {
      com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
      val selected = com.intellij.openapi.application.ReadAction.compute<BookmarkSourceNavigation.Selection, RuntimeException> {
        BookmarkSourceNavigation.selected(project, event.dataContext)
      }
      if (selected.points.isEmpty()) {
        Messages.showInfoMessage(project, "请选择具体的本地源码结果；目录、分组及反编译库文件不能作为行书签加入。", "加入书签"); return
      }
      BookmarkSourceResultsDialog(project, manager, selected).show()
    } catch (error: IllegalArgumentException) { Messages.showErrorDialog(project, error.localizedMessage.orEmpty(), "加入书签") }
  }
}
