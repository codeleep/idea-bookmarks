package com.intellij.ide.bookmark.ui

import com.intellij.ide.bookmark.*
import com.intellij.openapi.Disposable
import com.intellij.ui.SearchTextField
import com.intellij.ui.DocumentAdapter
import javax.swing.event.DocumentEvent
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBList
import com.intellij.util.SingleAlarm
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

internal data class BookmarkChoice(val id: String, val label: String) { override fun toString(): String = label }

internal fun groupChoices(state: ManagerState): List<BookmarkChoice> {
  val groups = state.groups.associateBy { it.id }
  return state.groups.map { group ->
    val names = mutableListOf(group.name); var parent = group.parentId
    while (parent != null) { val node = groups.getValue(parent); names.add(node.name); parent = node.parentId }
    BookmarkChoice(group.id!!, names.asReversed().joinToString(" / "))
  }
}

/** Docked reader reuses the existing route IDs, custom order and progress. */
class BookmarkReadingPanel(private val project: Project, private val manager: BookmarksManagerImpl,
                            private val initialGroup: String?) : JPanel(BorderLayout()), Disposable {
  private val store = BookmarkWorkspaceStore.getInstance(project)
  private val groups = ComboBox<BookmarkChoice>()
  private val list = JBList<String>()
  private val description = markdownPane { id -> BookmarkNavigation.locate(project, manager, id) }
  private val search = SearchTextField(false)
  private val open = JButton("打开当前代码")
  private var impacts = emptyMap<String, BookmarkImpact.Entry>()
  private val progress = JLabel()
  private val toggle = JButton("标为已读")
  private val previous = JButton("上一站")
  private val next = JButton("下一站")
  private val reorder = JButton("调整顺序…")
  private val reset = JButton("重置进度…")
  private var state: ManagerState? = null
  private var revision = -1L
  private var route: BookmarkRoute? = null
  private var entries = emptyMap<String, BookmarkSearchIndex.Entry>()
  private var configuring = false
  private val alarm = SingleAlarm({ refresh() }, 100, this, ModalityState.any())

  override fun dispose() = Unit

  fun selectGroup(id: String?) {
    refresh()
    if (id != null) (0 until groups.itemCount).firstOrNull { groups.getItemAt(it).id == id }?.let { groups.selectedIndex = it }
  }

  fun updateImpacts(value: Map<String, BookmarkImpact.Entry>) { impacts = value; list.repaint() }

  private fun renderRoute() {
    val matching = state?.let { BookmarkSearchIndex.fromState(it, project.basePath).search(search.text).map { entry -> entry.id }.toSet() }.orEmpty()
    val ids = route?.order.orEmpty().filter { it in matching }
    list.setListData(ids.toTypedArray())
    if (ids.isNotEmpty()) list.selectedIndex = ids.indexOf(route?.currentId).coerceAtLeast(0)
  }

  private fun currentGroup() = (groups.selectedItem as? BookmarkChoice)?.id

  fun refresh() {
    if (manager.isRestoringBookmarks()) { progress.text = "书签正在恢复…"; open.setEnabled(false); return }
    val snapshot = try { manager.captureForExchange() } catch (error: Exception) {
      configuring = true; state = null; route = null; list.setListData(emptyArray()); configuring = false
      selectionChanged(); progress.text = "无法读取书签：${error.localizedMessage.orEmpty()}"; return
    }
    val selected = currentGroup() ?: initialGroup
    configuring = true
    val choices = groupChoices(snapshot)
    if ((0 until groups.itemCount).map { groups.getItemAt(it) } != choices) {
      groups.model = DefaultComboBoxModel(choices.toTypedArray())
      choices.indexOfFirst { it.id == selected }.takeIf { it >= 0 }?.let { groups.selectedIndex = it }
    }
    state = snapshot; revision = manager.stateModificationCount
    entries = BookmarkSearchIndex.fromState(snapshot, project.basePath).entries.associateBy { it.id }
    route = currentGroup()?.let { store.route(snapshot, it) }
    renderRoute()
    configuring = false; selectionChanged()
    list.emptyText.text = "该分组及其子分组没有书签"
  }

  private fun selectionChanged() {
    if (configuring) return
    val selected = list.selectedValue; val current = route
    if (current != null && selected != null) {
      current.currentId = selected
      save()
    }
    val record = state?.bookmarks?.firstOrNull { it.id == selected }
    description.text = MarkdownDescriptions.toHtml(record?.description.orEmpty())
    description.caretPosition = 0
    val position = if (selected == null) -1 else current?.order?.indexOf(selected) ?: -1
    progress.text = "已读 ${current?.completed?.size ?: 0} / ${current?.order?.size ?: 0} · 第 ${if (position < 0) 0 else position + 1} 站"
    toggle.text = if (selected in current?.completed.orEmpty()) "标为未读" else "标为已读"
    toggle.isEnabled = selected != null; previous.isEnabled = list.selectedIndex > 0
    next.isEnabled = list.selectedIndex >= 0 && list.selectedIndex < list.model.size - 1
    reorder.isEnabled = search.text.isBlank() && (current?.order?.size ?: 0) > 1
    reset.isEnabled = current?.order?.isNotEmpty() == true
    open.setEnabled(selected != null && !manager.isRestoringBookmarks())
  }

  private fun save(): Boolean {
    val snapshot = state ?: return false; val current = route ?: return false
    if (manager.isRestoringBookmarks() || revision != manager.stateModificationCount) { alarm.cancelAndRequest(); return false }
    store.updateRoute(snapshot, current); return true
  }

  private fun step(delta: Int) {
    val to = list.selectedIndex + delta
    if (to !in 0 until list.model.size) return
    list.selectedIndex = to; openCurrent()
  }

  fun openCurrent() {
    val id = list.selectedValue ?: return
    if (!save()) return
    if (!BookmarkNavigation.locate(project, manager, id, currentGroup())) progress.text = "该书签无法跳转，请使用失效书签巡检；阅读进度已保留。"
  }

  internal fun toolWindowMenu() = JPopupMenu().apply {
    add(BookmarkWorkbenchUi.item(open)); add(BookmarkWorkbenchUi.item(reorder))
    addSeparator()
    add(JMenuItem("查看变更影响").apply {
      isEnabled = list.selectedValue != null
      addActionListener { BookmarkWorkbenchFactory.open(project, 1, currentGroup(), list.selectedValue) }
    })
    add(JMenuItem("查看关联").apply {
      isEnabled = list.selectedValue != null
      addActionListener { BookmarkWorkbenchFactory.open(project, 2, currentGroup(), list.selectedValue) }
    })
    addSeparator(); add(BookmarkWorkbenchUi.item(reset))
  }

  private fun createPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
    minimumSize = Dimension(JBUI.scale(300), JBUI.scale(200))
    add(JPanel(BorderLayout(0, JBUI.scale(6))).apply {
      add(BookmarkWorkbenchUi.heading(groups, BookmarkWorkbenchUi.refreshButton { refresh() }), BorderLayout.NORTH)
      add(search, BorderLayout.SOUTH)
    }, BorderLayout.NORTH)
    add(JSplitPane(JSplitPane.VERTICAL_SPLIT, ScrollPaneFactory.createScrollPane(list), ScrollPaneFactory.createScrollPane(description)).apply { resizeWeight = 0.55 }, BorderLayout.CENTER)
    add(JPanel(BorderLayout()).apply {
      add(progress, BorderLayout.NORTH)
      add(BookmarkWorkbenchUi.actions(previous, next, toggle), BorderLayout.CENTER)
    }, BorderLayout.SOUTH)
  }

  init {
    list.selectionMode = ListSelectionModel.SINGLE_SELECTION
    list.cellRenderer = DefaultListCellRenderer().let { renderer -> ListCellRenderer { component, id, index, selected, focus ->
      val entry = entries[id]
      renderer.getListCellRendererComponent(component, "${(route?.order?.indexOf(id) ?: index) + 1}. ${if (id in route?.completed.orEmpty()) "✓" else "○"} ${entry?.location.orEmpty()}  ${entry?.snippet("").orEmpty()}${if (impacts[id]?.status in setOf(BookmarkImpact.Status.CHANGED, BookmarkImpact.Status.MOVED)) "  ⚠ 待复核" else ""}", index, selected, focus)
    } }
    list.addListSelectionListener { if (!it.valueIsAdjusting) selectionChanged() }
    list.addMouseListener(object : MouseAdapter() { override fun mouseClicked(event: MouseEvent) {
      val index = list.locationToIndex(event.point)
      if (event.button == MouseEvent.BUTTON1 && event.clickCount == 2 && index >= 0 && list.getCellBounds(index, index).contains(event.point)) openCurrent()
    } })
    groups.addActionListener { if (!configuring) refresh() }
    previous.addActionListener { step(-1) }; next.addActionListener { step(1) }
    reorder.addActionListener {
      val snapshot = state; val group = currentGroup()
      if (snapshot != null && group != null && save() &&
          BookmarkRouteOrderDialog(project, manager, snapshot, revision, group, list.selectedValue).showAndGet()) refresh()
    }
    reset.addActionListener {
      if (Messages.showYesNoDialog(project, "重置这条路线的已读标记并返回首站？阅读顺序会保留。", "重置阅读进度", null) == Messages.YES) {
        route?.let { it.completed.clear(); it.currentId = it.order.firstOrNull().orEmpty(); if (save()) refresh() }
      }
    }
    toggle.addActionListener {
      val id = list.selectedValue
      route?.let { if (id != null) { if (!it.completed.remove(id)) it.completed.add(id); if (save()) { list.repaint(); selectionChanged() } } }
    }
    listenToBookmarks(project, this) { alarm.cancelAndRequest() }
    open.addActionListener { openCurrent() }
    list.toolTipText = "双击或按 Enter 打开代码；在工具窗口右上角三个点菜单中调整阅读顺序"
    list.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ENTER"), "openBookmark")
    list.actionMap.put("openBookmark", object : AbstractAction() { override fun actionPerformed(event: ActionEvent) { openCurrent() } })
    search.toolTipText = "全文搜索这条路线中的书签"
    search.textEditor.document.addDocumentListener(object : DocumentAdapter() { override fun textChanged(event: DocumentEvent) { configuring = true; renderRoute(); configuring = false; selectionChanged() } })
    add(createPanel(), BorderLayout.CENTER); refresh()
  }
}
