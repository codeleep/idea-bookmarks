package com.intellij.ide.bookmark.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.bookmark.Bookmark
import com.intellij.ide.bookmark.BookmarkGroup
import com.intellij.ide.bookmark.BookmarkSearchIndex
import com.intellij.ide.bookmark.BookmarksDevBundle.message
import com.intellij.ide.bookmark.BookmarksListener
import com.intellij.ide.bookmark.BookmarksManagerImpl
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.util.SingleAlarm
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.KeyAdapter
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.AbstractAction
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.event.DocumentEvent

class BookmarkSearchDialog(private val project: Project, private val manager: BookmarksManagerImpl,
                           private val chosen: (BookmarkSearchIndex.Entry, String) -> Unit) : DialogWrapper(project, true) {
  private val search = SearchTextField(false)
  private val list = JBList<BookmarkSearchIndex.Entry>()
  private val count = JLabel()
  private var index: BookmarkSearchIndex? = null
  private var revision = -1L
  private var live = emptyMap<String, Bookmark>()
  private val refreshAlarm = SingleAlarm(task = { refresh() }, delay = 100, parentDisposable = disposable,
                                         modalityState = ModalityState.any())

  private fun loading() {
    index = null; revision = -1; live = emptyMap()
    list.setListData(emptyArray<BookmarkSearchIndex.Entry>())
    count.text = message("search.loading"); setOKActionEnabled(false)
  }

  private fun refresh() {
    if (project.isDisposed || manager.isRestoringBookmarks()) { loading(); return }
    val selectedId = list.selectedValue?.id
    val before = manager.stateModificationCount
    if (before != revision) {
      val snapshot = try { manager.captureForExchange() } catch (_: IllegalStateException) { loading(); return }
      val bookmarks = manager.bookmarks.mapNotNull { bookmark -> manager.getBookmarkId(bookmark)?.let { it to bookmark } }.toMap()
      if (before != manager.stateModificationCount) { refreshAlarm.cancelAndRequest(); return }
      index = BookmarkSearchIndex.fromState(snapshot, project.basePath); revision = before; live = bookmarks
    }
    val results = index?.search(search.text).orEmpty()
    list.setListData(results.toTypedArray())
    if (results.isNotEmpty()) list.selectedIndex = results.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
    list.emptyText.text = message("search.empty")
    count.text = message("search.count", results.size, index?.entries?.size ?: 0)
    updateSelection()
  }

  private fun updateSelection() {
    setOKActionEnabled(list.selectedValue?.id in live)
    list.toolTipText = list.selectedValue?.groups?.joinToString("；", message("search.groups")) { it.path }
  }

  override fun getPreferredFocusedComponent(): JComponent = search.textEditor

  override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
    preferredSize = Dimension(JBUI.scale(800), JBUI.scale(460))
    add(JPanel(BorderLayout(0, JBUI.scale(6))).apply {
      add(search, BorderLayout.NORTH)
      add(JLabel(message("search.hint")), BorderLayout.CENTER)
      add(count, BorderLayout.SOUTH)
    }, BorderLayout.NORTH)
    add(ScrollPaneFactory.createScrollPane(list), BorderLayout.CENTER)
    add(JLabel(message("search.navigate.hint")), BorderLayout.SOUTH)
  }

  override fun doOKAction() {
    if (revision != manager.stateModificationCount || manager.isRestoringBookmarks()) { refresh(); return }
    val entry = list.selectedValue ?: return
    if (entry.id !in live) return
    val query = search.text
    super.doOKAction()
    chosen(entry, query)
  }

  init {
    title = message("search.title"); setOKButtonText(message("search.locate"))
    list.selectionMode = javax.swing.ListSelectionModel.SINGLE_SELECTION
    list.cellRenderer = object : ColoredListCellRenderer<BookmarkSearchIndex.Entry>() {
      override fun customizeCellRenderer(list: JList<out BookmarkSearchIndex.Entry>, value: BookmarkSearchIndex.Entry?, index: Int, selected: Boolean, hasFocus: Boolean) {
        if (value == null) return
        icon = AllIcons.Nodes.Bookmark
        append(value.location)
        val snippet = value.snippet(search.text)
        if (snippet.isNotEmpty()) append("  —  $snippet", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        append("  [${value.groups.joinToString("；") { it.path }}]", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        if (value.id !in live) append("  ${message("search.unavailable")}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
      }
    }
    list.addListSelectionListener { if (!it.valueIsAdjusting) updateSelection() }
    list.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "locateBookmark")
    list.actionMap.put("locateBookmark", object : AbstractAction() {
      override fun actionPerformed(event: ActionEvent?) = doOKAction()
    })
    list.addMouseListener(object : MouseAdapter() {
      override fun mouseClicked(event: MouseEvent) {
        val row = list.locationToIndex(event.point)
        if (event.clickCount == 2 && event.button == MouseEvent.BUTTON1 && row >= 0 && list.getCellBounds(row, row).contains(event.point)) doOKAction()
      }
    })
    search.textEditor.document.addDocumentListener(object : DocumentAdapter() {
      override fun textChanged(event: DocumentEvent) = refresh()
    })
    search.textEditor.addActionListener { doOKAction() }
    search.textEditor.addKeyListener(object : KeyAdapter() {
      override fun keyPressed(event: KeyEvent) {
        if (event.keyCode == KeyEvent.VK_DOWN && list.model.size > 0) { list.requestFocusInWindow(); event.consume() }
      }
    })
    project.messageBus.connect(disposable).subscribe(BookmarksListener.TOPIC, object : BookmarksListener {
      private fun changed() = refreshAlarm.cancelAndRequest()
      override fun groupsSorted() = changed()
      override fun groupAdded(group: BookmarkGroup) = changed()
      override fun groupRemoved(group: BookmarkGroup) = changed()
      override fun groupRenamed(group: BookmarkGroup) = changed()
      override fun bookmarksSorted(group: BookmarkGroup) = changed()
      override fun bookmarkAdded(group: BookmarkGroup, bookmark: Bookmark) = changed()
      override fun bookmarkRemoved(group: BookmarkGroup, bookmark: Bookmark) = changed()
      override fun bookmarkChanged(group: BookmarkGroup, bookmark: Bookmark) = changed()
      override fun bookmarkTypeChanged(bookmark: Bookmark) = changed()
      override fun defaultGroupChanged(oldGroup: BookmarkGroup?, newGroup: BookmarkGroup?) = changed()
      override fun structureChanged(node: Any?) = changed()
    })
    init(); refresh()
  }
}
