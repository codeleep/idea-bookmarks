package com.intellij.ide.bookmark.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.bookmark.*
import com.intellij.ide.bookmark.BookmarksDevBundle.message
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.util.SingleAlarm
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.event.*
import javax.swing.*
import javax.swing.event.DocumentEvent

class BookmarkSearchDialog(private val project: Project, private val manager: BookmarksManagerImpl,
                           private val chosen: (BookmarkSearchIndex.Entry, String) -> Unit) : DialogWrapper(project, true) {
  private val search = SearchTextField(false)
  private val list = JBList<BookmarkSearchIndex.Entry>()
  private val count = JLabel()
  private val groups = ComboBox<BookmarkChoice>()
  private val directory = JTextField(20)
  private val currentFile = JCheckBox("仅当前文件")
  private val description = ComboBox(arrayOf("描述：不限", "有描述", "无描述"))
  private val health = ComboBox(arrayOf("状态：不限", "正常", "失效", "未检查"))
  private val kind = ComboBox(arrayOf("类型：不限", "行书签", "文件书签", "其他提供器"))
  private val saved = ComboBox<BookmarkChoice>()
  private val store = BookmarkWorkspaceStore.getInstance(project)
  private var revision = -1L
  private var live = emptyMap<String, Bookmark>()
  private var generation = 0
  private var configuring = false
  private val refreshAlarm = SingleAlarm({ refresh() }, 100, disposable, ModalityState.any())

  private fun filter() = BookmarkFilter().also {
    it.query = search.text; it.groupId = (groups.selectedItem as? BookmarkChoice)?.id.orEmpty()
    it.directory = directory.text; it.currentFile = currentFile.isSelected
    it.description = arrayOf("ANY", "WITH", "WITHOUT")[description.selectedIndex]
    it.health = arrayOf("ANY", "VALID", "INVALID", "UNCHECKED")[health.selectedIndex]
    it.kind = arrayOf("ANY", "LINE", "FILE", "OTHER")[kind.selectedIndex]
  }

  private fun refreshViews(selectedId: String = "") {
    configuring = true
    saved.model = DefaultComboBoxModel((listOf(BookmarkChoice("", "选择保存视图…")) + store.views().map { BookmarkChoice(it.id, it.name) }).toTypedArray())
    (0 until saved.itemCount).firstOrNull { saved.getItemAt(it).id == selectedId }?.let { saved.selectedIndex = it }
    configuring = false
  }

  private fun applyFilter(value: BookmarkFilter) {
    configuring = true
    search.text = value.query; directory.text = value.directory; currentFile.isSelected = value.currentFile
    description.selectedIndex = arrayOf("ANY", "WITH", "WITHOUT").indexOf(value.description).coerceAtLeast(0)
    health.selectedIndex = arrayOf("ANY", "VALID", "INVALID", "UNCHECKED").indexOf(value.health).coerceAtLeast(0)
    kind.selectedIndex = arrayOf("ANY", "LINE", "FILE", "OTHER").indexOf(value.kind).coerceAtLeast(0)
    if ((0 until groups.itemCount).none { groups.getItemAt(it).id == value.groupId }) groups.addItem(BookmarkChoice(value.groupId, "当前分支无此分组（${value.groupId}）"))
    groups.selectedIndex = (0 until groups.itemCount).first { groups.getItemAt(it).id == value.groupId }
    configuring = false; refresh()
  }

  private fun refresh() {
    if (configuring || project.isDisposed) return
    val token = ++generation; val selectedId = list.selectedValue?.id
    setOKActionEnabled(false); revision = -1
    if (manager.isRestoringBookmarks()) {
      list.setListData(emptyArray<BookmarkSearchIndex.Entry>()); count.text = message("search.loading"); live = emptyMap(); return
    }
    val before = manager.stateModificationCount
    val snapshot = try { manager.captureForExchange() } catch (_: IllegalStateException) { count.text = message("search.loading"); return }
    val selectedGroup = (groups.selectedItem as? BookmarkChoice)?.id.orEmpty()
    val choices = (listOf(BookmarkChoice("", "所有分组（包含子分组）")) + groupChoices(snapshot)).toMutableList()
    if (choices.none { it.id == selectedGroup }) choices.add(BookmarkChoice(selectedGroup, "当前分支无此分组（$selectedGroup）"))
    if ((0 until groups.itemCount).map { groups.getItemAt(it) } != choices) {
      configuring = true
      groups.model = DefaultComboBoxModel(choices.toTypedArray())
      groups.selectedIndex = choices.indexOfFirst { it.id == selectedGroup }
      configuring = false
    }
    val condition = filter()
    val editorFile = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.url
    val bookmarks = manager.bookmarks.mapNotNull { item -> manager.getBookmarkId(item)?.let { it to item } }.toMap()
    count.text = "正在筛选…"
    ReadAction.nonBlocking<List<BookmarkSearchIndex.Entry>> {
      val statuses = if (condition.health == "ANY") emptyList() else BookmarkHealth.inspect(project, snapshot)
      BookmarkFilters.select(snapshot, project.basePath, condition, editorFile, statuses)
    }.coalesceBy(this).expireWith(disposable).finishOnUiThread(ModalityState.any()) { results ->
      if (token != generation) return@finishOnUiThread
      if (before != manager.stateModificationCount || manager.isRestoringBookmarks()) { refreshAlarm.cancelAndRequest(); return@finishOnUiThread }
      revision = before; live = bookmarks; list.setListData(results.toTypedArray())
      if (results.isNotEmpty()) list.selectedIndex = results.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
      list.emptyText.text = message("search.empty")
      count.text = message("search.count", results.size, snapshot.bookmarks.size); updateSelection()
    }.submit(AppExecutorUtil.getAppExecutorService())
  }

  private fun updateSelection() {
    setOKActionEnabled(revision == manager.stateModificationCount && list.selectedValue?.id?.let { live[it]?.canNavigate() } == true)
    list.toolTipText = list.selectedValue?.groups?.joinToString("；", message("search.groups")) { it.path }
  }

  override fun getPreferredFocusedComponent(): JComponent = search.textEditor
  override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
    preferredSize = Dimension(JBUI.scale(900), JBUI.scale(600))
    add(JPanel(GridLayout(0, 1, 0, JBUI.scale(6))).apply {
      add(search)
      add(JPanel(BorderLayout()).apply { add(JLabel("分组："), BorderLayout.WEST); add(groups, BorderLayout.CENTER) })
      add(JPanel(FlowLayout(FlowLayout.LEFT)).apply { add(JLabel("目录（项目相对路径或绝对路径）：")); add(directory); add(currentFile) })
      add(JPanel(FlowLayout(FlowLayout.LEFT)).apply { add(description); add(health); add(kind); add(JButton("刷新").apply { addActionListener { refresh() } }) })
      add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
        add(saved)
        add(JButton("保存视图…").apply { addActionListener {
          val name = Messages.showInputDialog(project, "保存当前全文查询和筛选条件：", "保存书签视图", null) ?: return@addActionListener
          try { val view = store.saveView(name, filter()); refreshViews(view.id) }
          catch (error: IllegalArgumentException) { Messages.showErrorDialog(project, error.message.orEmpty(), "保存书签视图") }
        } })
        add(JButton("删除视图").apply { addActionListener { (saved.selectedItem as? BookmarkChoice)?.id?.takeIf { it.isNotEmpty() }?.let { store.removeView(it); refreshViews() } } })
        add(JButton("清除筛选").apply { addActionListener { refreshViews(); applyFilter(BookmarkFilter()) } })
      }); add(count)
    }, BorderLayout.NORTH)
    add(ScrollPaneFactory.createScrollPane(list), BorderLayout.CENTER)
    add(JLabel("条件同时满足；保存视图保存查询条件，每次打开时使用当前上下文重新计算。"), BorderLayout.SOUTH)
  }

  override fun doOKAction() {
    if (revision != manager.stateModificationCount || manager.isRestoringBookmarks()) { refresh(); return }
    val entry = list.selectedValue ?: return
    if (live[entry.id]?.canNavigate() != true) return
    val query = search.text; super.doOKAction(); chosen(entry, query)
  }

  init {
    title = message("search.title") + " / 高级筛选与保存视图"; setOKButtonText(message("search.locate"))
    groups.addItem(BookmarkChoice("", "所有分组（包含子分组）")); refreshViews()
    list.selectionMode = ListSelectionModel.SINGLE_SELECTION
    list.cellRenderer = object : ColoredListCellRenderer<BookmarkSearchIndex.Entry>() {
      override fun customizeCellRenderer(list: JList<out BookmarkSearchIndex.Entry>, value: BookmarkSearchIndex.Entry?, index: Int, selected: Boolean, hasFocus: Boolean) {
        if (value == null) return
        icon = AllIcons.Nodes.Bookmark; append(value.location)
        val snippet = value.snippet(search.text)
        if (snippet.isNotEmpty()) append("  —  $snippet", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        append("  [${value.groups.joinToString("；") { it.path }}]", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        if (live[value.id]?.canNavigate() != true) append("  ${message("search.unavailable")}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
      }
    }
    list.addListSelectionListener { if (!it.valueIsAdjusting) updateSelection() }
    list.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "locateBookmark")
    list.actionMap.put("locateBookmark", object : AbstractAction() { override fun actionPerformed(event: ActionEvent?) = doOKAction() })
    list.addMouseListener(object : MouseAdapter() { override fun mouseClicked(event: MouseEvent) {
      val row = list.locationToIndex(event.point)
      if (event.clickCount == 2 && event.button == MouseEvent.BUTTON1 && row >= 0 && list.getCellBounds(row, row).contains(event.point)) doOKAction()
    } })
    val listener = object : DocumentAdapter() { override fun textChanged(event: DocumentEvent) {
      if (!configuring) { generation++; revision = -1; setOKActionEnabled(false); refreshAlarm.cancelAndRequest() }
    } }
    search.textEditor.document.addDocumentListener(listener); directory.document.addDocumentListener(listener)
    groups.addActionListener { if (!configuring) refresh() }; currentFile.addActionListener { refresh() }
    description.addActionListener { refresh() }; health.addActionListener { refresh() }; kind.addActionListener { refresh() }
    saved.addActionListener {
      if (!configuring) store.views().firstOrNull { it.id == (saved.selectedItem as? BookmarkChoice)?.id }?.let { applyFilter(it.filter) }
    }
    search.textEditor.addActionListener { doOKAction() }
    search.textEditor.addKeyListener(object : KeyAdapter() { override fun keyPressed(event: KeyEvent) {
      if (event.keyCode == KeyEvent.VK_DOWN && list.model.size > 0) { list.requestFocusInWindow(); event.consume() }
    } })
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
