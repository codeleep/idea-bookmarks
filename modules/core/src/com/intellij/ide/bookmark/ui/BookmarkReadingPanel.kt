package com.intellij.ide.bookmark.ui

import com.intellij.ide.bookmark.*
import com.intellij.openapi.Disposable
import com.intellij.ui.SearchTextField
import com.intellij.ui.DocumentAdapter
import javax.swing.event.DocumentEvent
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBList
import com.intellij.util.SingleAlarm
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
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
  private val moveUp = JButton("上移")
  private val moveDown = JButton("下移")
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
    progress.text = "已读 ${current?.completed?.size ?: 0} / ${current?.order?.size ?: 0}，当前第 ${if (position < 0) 0 else position + 1} 站"
    toggle.text = if (selected in current?.completed.orEmpty()) "标为未读" else "标为已读"
    toggle.isEnabled = selected != null; previous.isEnabled = list.selectedIndex > 0
    next.isEnabled = list.selectedIndex >= 0 && list.selectedIndex < list.model.size - 1
    moveUp.isEnabled = search.text.isBlank() && position > 0; moveDown.isEnabled = search.text.isBlank() && next.isEnabled
    open.setEnabled(selected != null && !manager.isRestoringBookmarks())
  }

  private fun save(): Boolean {
    val snapshot = state ?: return false; val current = route ?: return false
    if (manager.isRestoringBookmarks() || revision != manager.stateModificationCount) { alarm.cancelAndRequest(); return false }
    store.updateRoute(snapshot, current); return true
  }

  private fun move(delta: Int) {
    val current = route ?: return; val from = list.selectedIndex; val to = from + delta
    if (from !in current.order.indices || to !in current.order.indices) return
    java.util.Collections.swap(current.order, from, to)
    if (!save()) return
    configuring = true; list.setListData(current.order.toTypedArray()); list.selectedIndex = to; configuring = false; selectionChanged()
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

  private fun createPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
    minimumSize = Dimension(JBUI.scale(300), JBUI.scale(200))
    add(JPanel(BorderLayout(0, JBUI.scale(6))).apply {
      add(JPanel(BorderLayout()).apply { add(groups, BorderLayout.CENTER); add(JButton("刷新").apply { addActionListener { refresh() } }, BorderLayout.EAST) }, BorderLayout.NORTH)
      add(search, BorderLayout.SOUTH)
    }, BorderLayout.NORTH)
    add(JSplitPane(JSplitPane.VERTICAL_SPLIT, ScrollPaneFactory.createScrollPane(list), ScrollPaneFactory.createScrollPane(description)).apply { resizeWeight = 0.55 }, BorderLayout.CENTER)
    add(JPanel(BorderLayout()).apply {
      add(progress, BorderLayout.NORTH)
      add(JPanel(GridLayout(0, 2, JBUI.scale(6), JBUI.scale(6))).apply {
        add(open); add(previous); add(next); add(toggle); add(moveUp); add(moveDown)
        add(JButton("查看变更影响").apply { addActionListener { BookmarkWorkbenchFactory.open(project, 1, currentGroup(), list.selectedValue) } })
        add(JButton("查看关联").apply { addActionListener { BookmarkWorkbenchFactory.open(project, 2, currentGroup(), list.selectedValue) } })
        add(JButton("重置进度").apply { addActionListener {
          route?.let { it.completed.clear(); it.currentId = it.order.firstOrNull().orEmpty(); if (save()) refresh() }
        } })
      }, BorderLayout.CENTER)
      add(JTextArea("包含子分组，共享书签只读一次。路线排序和进度随原分支上下文恢复。", 2, 20).apply { isEditable = false; isOpaque = false; lineWrap = true; wrapStyleWord = true }, BorderLayout.SOUTH)
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
    moveUp.addActionListener { move(-1) }; moveDown.addActionListener { move(1) }
    toggle.addActionListener {
      val id = list.selectedValue
      route?.let { if (id != null) { if (!it.completed.remove(id)) it.completed.add(id); if (save()) { list.repaint(); selectionChanged() } } }
    }
    listenToBookmarks(project, this) { alarm.cancelAndRequest() }
    open.addActionListener { openCurrent() }
    search.textEditor.document.addDocumentListener(object : DocumentAdapter() { override fun textChanged(event: DocumentEvent) { configuring = true; renderRoute(); configuring = false; selectionChanged() } })
    add(createPanel(), BorderLayout.CENTER); refresh()
  }
}
