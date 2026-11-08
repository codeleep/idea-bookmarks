package com.intellij.ide.bookmark.ui

import com.intellij.ide.bookmark.*
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.Disposer
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBList
import com.intellij.util.SingleAlarm
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
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

/** Modeless: the route stays available while the user reads and edits source. */
class BookmarkReadingDialog(private val project: Project, private val manager: BookmarksManagerImpl,
                            private val initialGroup: String?) : DialogWrapper(project, false) {
  companion object {
    private val KEY = Key.create<BookmarkReadingDialog>("bookmarks.reading.dialog")
    fun open(project: Project, manager: BookmarksManagerImpl, groupId: String?) {
      project.getUserData(KEY)?.let { existing ->
        existing.refresh()
        if (groupId != null) (0 until existing.groups.itemCount).firstOrNull { existing.groups.getItemAt(it).id == groupId }?.let { existing.groups.selectedIndex = it }
        existing.window.toFront(); existing.window.requestFocus(); return
      }
      val dialog = BookmarkReadingDialog(project, manager, groupId)
      project.putUserData(KEY, dialog)
      Disposer.register(dialog.disposable) { if (project.getUserData(KEY) === dialog) project.putUserData(KEY, null) }
      dialog.show()
    }
  }
  private val store = BookmarkWorkspaceStore.getInstance(project)
  private val groups = ComboBox<BookmarkChoice>()
  private val list = JBList<String>()
  private val description = markdownPane()
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
  private val alarm = SingleAlarm({ refresh() }, 100, disposable, ModalityState.any())

  private fun currentGroup() = (groups.selectedItem as? BookmarkChoice)?.id

  fun refresh() {
    if (manager.isRestoringBookmarks()) { progress.text = "书签正在恢复…"; setOKActionEnabled(false); return }
    val snapshot = runCatching { manager.captureForExchange() }.getOrNull() ?: return
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
    list.setListData(route?.order?.toTypedArray() ?: emptyArray())
    route?.let { if (it.currentId in it.order) list.selectedIndex = it.order.indexOf(it.currentId) }
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
    val position = list.selectedIndex
    progress.text = "已读 ${current?.completed?.size ?: 0} / ${current?.order?.size ?: 0}，当前第 ${if (position < 0) 0 else position + 1} 站"
    toggle.text = if (selected in current?.completed.orEmpty()) "标为未读" else "标为已读"
    toggle.isEnabled = selected != null; previous.isEnabled = position > 0
    next.isEnabled = current != null && position >= 0 && position < current.order.lastIndex
    moveUp.isEnabled = position > 0; moveDown.isEnabled = next.isEnabled
    setOKActionEnabled(selected != null && !manager.isRestoringBookmarks())
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
    list.selectedIndex = to; doOKAction()
  }

  override fun doOKAction() {
    val id = list.selectedValue ?: return
    if (!save()) return
    if (!BookmarkNavigation.locate(project, manager, id, currentGroup())) progress.text = "该书签无法跳转，请使用失效书签巡检；阅读进度已保留。"
  }

  override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
    preferredSize = Dimension(JBUI.scale(820), JBUI.scale(560))
    add(JPanel(BorderLayout()).apply { add(groups, BorderLayout.CENTER); add(JButton("刷新").apply { addActionListener { refresh() } }, BorderLayout.EAST) }, BorderLayout.NORTH)
    add(JSplitPane(JSplitPane.VERTICAL_SPLIT, ScrollPaneFactory.createScrollPane(list), ScrollPaneFactory.createScrollPane(description)).apply { resizeWeight = 0.55 }, BorderLayout.CENTER)
    add(JPanel(BorderLayout()).apply {
      add(progress, BorderLayout.NORTH)
      add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
        add(previous); add(next); add(toggle); add(moveUp); add(moveDown)
        add(JButton("重置进度").apply { addActionListener {
          route?.let { it.completed.clear(); it.currentId = it.order.firstOrNull().orEmpty(); if (save()) refresh() }
        } })
      }, BorderLayout.CENTER)
      add(JLabel("包含所选分组及子分组，共享书签只读一次；路线顺序独立保存，进度随书签分支上下文恢复。"), BorderLayout.SOUTH)
    }, BorderLayout.SOUTH)
  }

  init {
    title = "代码阅读路线"; setModal(false); setOKButtonText("打开当前代码"); setCancelButtonText("关闭")
    list.selectionMode = ListSelectionModel.SINGLE_SELECTION
    list.cellRenderer = DefaultListCellRenderer().let { renderer -> ListCellRenderer { component, id, index, selected, focus ->
      val entry = entries[id]
      renderer.getListCellRendererComponent(component, "${index + 1}. ${if (id in route?.completed.orEmpty()) "✓" else "○"} ${entry?.location.orEmpty()}  ${entry?.snippet("").orEmpty()}", index, selected, focus)
    } }
    list.addListSelectionListener { if (!it.valueIsAdjusting) selectionChanged() }
    list.addMouseListener(object : MouseAdapter() { override fun mouseClicked(event: MouseEvent) {
      val index = list.locationToIndex(event.point)
      if (event.button == MouseEvent.BUTTON1 && event.clickCount == 2 && index >= 0 && list.getCellBounds(index, index).contains(event.point)) doOKAction()
    } })
    groups.addActionListener { if (!configuring) refresh() }
    previous.addActionListener { step(-1) }; next.addActionListener { step(1) }
    moveUp.addActionListener { move(-1) }; moveDown.addActionListener { move(1) }
    toggle.addActionListener {
      val id = list.selectedValue
      route?.let { if (id != null) { if (!it.completed.remove(id)) it.completed.add(id); if (save()) { list.repaint(); selectionChanged() } } }
    }
    project.messageBus.connect(disposable).subscribe(BookmarksListener.TOPIC, object : BookmarksListener {
      private fun changed() = alarm.cancelAndRequest()
      override fun groupsSorted() = changed()
      override fun groupAdded(group: BookmarkGroup) = changed()
      override fun groupRemoved(group: BookmarkGroup) = changed()
      override fun groupRenamed(group: BookmarkGroup) = changed()
      override fun bookmarksSorted(group: BookmarkGroup) = changed()
      override fun bookmarkAdded(group: BookmarkGroup, bookmark: Bookmark) = changed()
      override fun bookmarkRemoved(group: BookmarkGroup, bookmark: Bookmark) = changed()
      override fun bookmarkChanged(group: BookmarkGroup, bookmark: Bookmark) = changed()
      override fun structureChanged(node: Any?) = changed()
    })
    init(); refresh()
  }
}
