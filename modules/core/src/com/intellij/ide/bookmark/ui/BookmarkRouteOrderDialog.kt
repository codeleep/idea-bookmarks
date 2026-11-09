package com.intellij.ide.bookmark.ui

import com.intellij.ide.bookmark.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ActionEvent
import javax.swing.*

/** Reordering is a separate, cancellable edit; route navigation never changes order. */
internal class BookmarkRouteOrderDialog(
  project: Project, private val manager: BookmarksManagerImpl, private val snapshot: ManagerState,
  private val revision: Long, private val groupId: String, selectedId: String?,
) : DialogWrapper(project, true) {
  private val store = BookmarkWorkspaceStore.getInstance(project)
  private val order = store.route(snapshot, groupId).order.toMutableList()
  private val entries = BookmarkSearchIndex.fromState(snapshot, project.basePath).entries.associateBy { it.id }
  private val list = JBList<String>()
  private val up = JButton("上移一站")
  private val down = JButton("下移一站")

  private fun selected() {
    up.isEnabled = list.selectedIndex > 0
    down.isEnabled = list.selectedIndex >= 0 && list.selectedIndex < order.lastIndex
  }

  private fun move(delta: Int) {
    val from = list.selectedIndex; val to = from + delta
    if (from !in order.indices || to !in order.indices) return
    java.util.Collections.swap(order, from, to)
    list.setListData(order.toTypedArray()); list.selectedIndex = to; list.ensureIndexIsVisible(to)
  }

  override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
    preferredSize = Dimension(JBUI.scale(620), JBUI.scale(400))
    add(JLabel("仅调整这条阅读路线，分组顺序和已读进度保持不变。"), BorderLayout.NORTH)
    add(ScrollPaneFactory.createScrollPane(list), BorderLayout.CENTER)
    add(BookmarkWorkbenchUi.actions(up, down), BorderLayout.SOUTH)
  }

  override fun getPreferredFocusedComponent(): JComponent = list

  override fun doOKAction() {
    try {
      check(!manager.isRestoringBookmarks() && manager.stateModificationCount == revision &&
            BookmarkExchange.toJson(manager.captureForExchange(), null) == BookmarkExchange.toJson(snapshot, null)) {
        "书签或分支已经变化，请重新打开调整顺序"
      }
      store.reorderRoute(snapshot, groupId, order)
      super.doOKAction()
    } catch (error: Exception) { Messages.showErrorDialog(error.localizedMessage.orEmpty(), title) }
  }

  init {
    title = "调整阅读顺序"; setOKButtonText("应用顺序")
    list.selectionMode = ListSelectionModel.SINGLE_SELECTION
    list.cellRenderer = DefaultListCellRenderer().let { renderer -> ListCellRenderer { component, id, row, selected, focus ->
      val entry = entries[id]
      renderer.getListCellRendererComponent(component, "${row + 1}. ${entry?.location.orEmpty()} · ${entry?.snippet("").orEmpty()}", row, selected, focus)
    } }
    list.setListData(order.toTypedArray())
    if (order.isNotEmpty()) list.selectedIndex = order.indexOf(selectedId).coerceAtLeast(0)
    list.addListSelectionListener { selected() }
    up.addActionListener { move(-1) }; down.addActionListener { move(1) }
    for ((key, delta) in listOf("alt UP" to -1, "alt DOWN" to 1)) {
      list.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), key)
      list.actionMap.put(key, object : AbstractAction() { override fun actionPerformed(event: ActionEvent) { move(delta) } })
    }
    up.toolTipText = "上移一站（Alt+↑）"; down.toolTipText = "下移一站（Alt+↓）"
    init(); selected()
  }
}
