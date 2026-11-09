package com.intellij.ide.bookmark.ui

import com.intellij.ide.bookmark.*
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBList
import com.intellij.util.SingleAlarm
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.event.DocumentEvent

class BookmarkRelationsPanel(private val project: Project, private val manager: BookmarksManagerImpl) : JPanel(BorderLayout()), Disposable {
  private val search = SearchTextField(false)
  private val sources = JBList<BookmarkSearchIndex.Entry>()
  private val edges = JBList<BookmarkRelation>()
  private val details = JTextArea().apply { isEditable = false; lineWrap = true }
  private val summary = JLabel()
  private val add = JButton("新建关联…")
  private val edit = JButton("编辑关联…")
  private val delete = JButton("删除关联")
  private val open = JButton("打开代码")
  private val target = JButton("查看目标关联")
  private val copyLink = JButton("复制当前书签链接")
  private var state: ManagerState? = null
  private var revision = -1L
  private var index: BookmarkSearchIndex? = null
  private val alarm = SingleAlarm({ refresh() }, 100, this, ModalityState.any())

  fun selectBookmark(id: String?) {
    if (id != null) search.text = ""
    refresh(id)
  }
  private fun refresh(preferred: String? = sources.selectedValue?.id) {
    if (manager.isRestoringBookmarks()) { summary.text = "书签正在恢复…"; add.isEnabled = false; edit.isEnabled = false; delete.isEnabled = false; return }
    val snapshot = try { manager.captureForExchange() } catch (error: Exception) {
      state = null; sources.setListData(emptyArray()); sourceChanged()
      summary.text = "无法读取书签：${error.localizedMessage.orEmpty()}"; return
    }
    state = snapshot; revision = manager.stateModificationCount; index = BookmarkSearchIndex.fromState(snapshot, project.basePath)
    val records = index!!.search(search.text)
    sources.setListData(records.toTypedArray())
    if (records.isNotEmpty()) sources.selectedIndex = records.indexOfFirst { it.id == preferred }.coerceAtLeast(0)
    summary.text = "${snapshot.bookmarks.size} 个书签 · ${snapshot.relations.size} 条关联"
    sources.emptyText.text = "没有匹配的书签"; sourceChanged()
  }
  private fun current() = !manager.isRestoringBookmarks() && revision == manager.stateModificationCount
  private fun sourceChanged() {
    val source = sources.selectedValue?.id
    edges.setListData(state?.relations?.filter { it.sourceId == source || it.targetId == source }?.toTypedArray() ?: emptyArray())
    if (edges.model.size > 0) edges.selectedIndex = 0
    add.isEnabled = current() && source != null && (state?.bookmarks?.size ?: 0) > 1
    copyLink.isEnabled = current() && source != null
    edges.emptyText.text = "该书签尚无关联"; edgeChanged()
  }
  private fun other(edge: BookmarkRelation): String = if (edge.sourceId == sources.selectedValue?.id) edge.targetId else edge.sourceId
  private fun label(id: String): String = index?.entries?.firstOrNull { it.id == id }?.let { "${it.location} · ${it.snippet("")}" } ?: id
  private fun edgeChanged() {
    val edge = edges.selectedValue
    edit.isEnabled = current() && edge != null; delete.isEnabled = edit.isEnabled; open.isEnabled = current() && edge != null
    target.isEnabled = open.isEnabled
    details.text = edge?.let { "${label(it.sourceId)}\n  ${BookmarkRelations.KINDS[it.kind]} →\n${label(it.targetId)}\n\n${it.note}\n\n关联 ID：${it.id}" }.orEmpty()
    details.caretPosition = 0
  }
  private fun apply(snapshot: ManagerState, expectedRevision: Long, proposed: ManagerState): Boolean {
    return try {
      manager.applyExchange(snapshot, expectedRevision, proposed); summary.text = "关联已保存；变更前的完整书签状态已备份。"
      alarm.cancelAndRequest(); true
    } catch (error: Exception) { Messages.showErrorDialog(project, error.localizedMessage.orEmpty(), "书签关联"); false }
  }
  private fun editRelation(original: BookmarkRelation?) {
    val snapshot = state ?: return
    if (!current()) { refresh(); return }
    val source = original?.sourceId ?: sources.selectedValue?.id ?: return
    val expectedRevision = revision
    BookmarkRelationDialog(project, snapshot, source, original) { proposed -> apply(snapshot, expectedRevision, proposed) }.show()
  }
  private fun navigate() {
    if (!current()) { refresh(); return }
    val edge = edges.selectedValue ?: return
    if (!BookmarkNavigation.locate(project, manager, other(edge))) summary.text = "关联目标暂时无法跳转，请先巡检或启用对应提供器。"
  }
  override fun dispose() = Unit
  internal fun toolWindowMenu() = JPopupMenu().apply {
    add(BookmarkWorkbenchUi.item(edit)); add(BookmarkWorkbenchUi.item(delete))
    addSeparator(); add(BookmarkWorkbenchUi.item(target)); add(BookmarkWorkbenchUi.item(copyLink))
  }
  init {
    border = JBUI.Borders.empty(6)
    add(JPanel(BorderLayout(0, JBUI.scale(6))).apply {
      add(BookmarkWorkbenchUi.heading(summary, BookmarkWorkbenchUi.refreshButton { refresh() }), BorderLayout.NORTH)
      add(search, BorderLayout.SOUTH)
    }, BorderLayout.NORTH)
    add(JSplitPane(JSplitPane.VERTICAL_SPLIT, ScrollPaneFactory.createScrollPane(sources),
      JSplitPane(JSplitPane.VERTICAL_SPLIT, ScrollPaneFactory.createScrollPane(edges), ScrollPaneFactory.createScrollPane(details)).apply { resizeWeight = 0.6 }).apply { resizeWeight = 0.45 }, BorderLayout.CENTER)
    add(BookmarkWorkbenchUi.actions(add, open), BorderLayout.SOUTH)
    target.addActionListener { edges.selectedValue?.let { selectBookmark(other(it)) } }
    copyLink.addActionListener {
      sources.selectedValue?.id?.let { id ->
        com.intellij.openapi.ide.CopyPasteManager.getInstance().setContents(java.awt.datatransfer.StringSelection(BookmarkLinks.url(id)))
      }
    }
    open.toolTipText = "打开所选关联另一端的代码；也可以双击关联"
    edges.toolTipText = "双击跳转；右键或工具窗口右上角三个点菜单可管理关联"
    BookmarkWorkbenchUi.installMenu(edges) { toolWindowMenu() }
    sources.selectionMode = ListSelectionModel.SINGLE_SELECTION; edges.selectionMode = ListSelectionModel.SINGLE_SELECTION
    sources.cellRenderer = DefaultListCellRenderer().let { renderer -> ListCellRenderer { component, value, row, selected, focus ->
      renderer.getListCellRendererComponent(component, "${value.location} · ${value.snippet(search.text)}", row, selected, focus)
    } }
    edges.cellRenderer = DefaultListCellRenderer().let { renderer -> ListCellRenderer { component, edge, row, selected, focus ->
      val direction = if (edge.kind == "RELATED") "↔" else if (edge.sourceId == sources.selectedValue?.id) "→" else "←"
      renderer.getListCellRendererComponent(component, "$direction ${BookmarkRelations.KINDS[edge.kind]} · ${label(other(edge))}", row, selected, focus)
    } }
    sources.addListSelectionListener { if (!it.valueIsAdjusting) sourceChanged() }; edges.addListSelectionListener { if (!it.valueIsAdjusting) edgeChanged() }
    edges.addMouseListener(object : MouseAdapter() { override fun mouseClicked(event: MouseEvent) {
      val row = edges.locationToIndex(event.point)
      if (event.button == MouseEvent.BUTTON1 && event.clickCount == 2 && row >= 0 && edges.getCellBounds(row, row).contains(event.point)) navigate()
    } })
    search.textEditor.document.addDocumentListener(object : DocumentAdapter() { override fun textChanged(event: DocumentEvent) = refresh() })
    add.addActionListener { editRelation(null) }; edit.addActionListener { edges.selectedValue?.let { editRelation(it) } }; open.addActionListener { navigate() }
    delete.addActionListener {
      val snapshot = state; val edge = edges.selectedValue
      val expectedRevision = revision
      if (snapshot != null && edge != null && current() && Messages.showYesNoDialog(project,
          "删除“${BookmarkRelations.KINDS[edge.kind]}”关联？两端书签及分组会保留。", "删除书签关联", null) == Messages.YES) apply(snapshot, expectedRevision, BookmarkRelations.remove(snapshot, edge.id))
    }
    listenToBookmarks(project, this) { alarm.cancelAndRequest() }; refresh()
  }
}

private class BookmarkRelationDialog(project: Project, private val state: ManagerState, private val source: String,
                                     private val original: BookmarkRelation?, private val apply: (ManagerState) -> Boolean) : DialogWrapper(project, true) {
  private val search = SearchTextField(false)
  private val targets = JBList<BookmarkSearchIndex.Entry>()
  private val kind = ComboBox(BookmarkRelations.KINDS.map { BookmarkChoice(it.key, it.value) }.toTypedArray())
  private val note = JTextArea(original?.note.orEmpty(), 5, 30).apply { lineWrap = true }
  private val index = BookmarkSearchIndex.fromState(state, project.basePath)
  private fun filter() {
    val wanted = targets.selectedValue?.id ?: original?.targetId
    val choices = index.search(search.text).filter { it.id != source }
    targets.setListData(choices.toTypedArray())
    if (choices.isNotEmpty()) targets.selectedIndex = choices.indexOfFirst { it.id == wanted }.coerceAtLeast(0)
    setOKActionEnabled(targets.selectedValue != null)
  }
  override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
    preferredSize = Dimension(JBUI.scale(700), JBUI.scale(480))
    add(JPanel(BorderLayout()).apply { add(JLabel("起点：${index.entries.first { it.id == source }.location}"), BorderLayout.NORTH); add(search, BorderLayout.SOUTH) }, BorderLayout.NORTH)
    add(ScrollPaneFactory.createScrollPane(targets), BorderLayout.CENTER)
    add(JPanel(BorderLayout()).apply { add(kind, BorderLayout.NORTH); add(ScrollPaneFactory.createScrollPane(note), BorderLayout.CENTER); add(JLabel("类型表示从起点到目标的关系；“相关”为双向。"), BorderLayout.SOUTH) }, BorderLayout.SOUTH)
  }
  override fun doOKAction() {
    val target = targets.selectedValue?.id ?: return
    try {
      val proposed = BookmarkExchange.copy(state).apply { formatVersion = BookmarkStorageFormat.VERSION }
      if (original != null) proposed.relations.removeIf { it.id == original.id }
      val edge = BookmarkRelation().also {
        it.id = original?.id ?: java.util.UUID.randomUUID().toString(); it.sourceId = source; it.targetId = target
        it.kind = (kind.selectedItem as BookmarkChoice).id; it.note = note.text
      }
      proposed.relations.add(edge); BookmarkStorageFormat.validate(proposed)
      if (apply(proposed)) super.doOKAction()
    } catch (error: Exception) { Messages.showErrorDialog(error.localizedMessage.orEmpty(), title) }
  }
  init {
    title = if (original == null) "新建书签关联" else "编辑书签关联"; setOKButtonText("保存关联")
    targets.selectionMode = ListSelectionModel.SINGLE_SELECTION
    targets.cellRenderer = DefaultListCellRenderer().let { renderer -> ListCellRenderer { component, entry, row, selected, focus ->
      renderer.getListCellRendererComponent(component, "${entry.location} · ${entry.snippet(search.text)}", row, selected, focus)
    } }
    (0 until kind.itemCount).firstOrNull { kind.getItemAt(it).id == original?.kind }?.let { kind.selectedIndex = it }
    targets.addListSelectionListener { setOKActionEnabled(targets.selectedValue != null) }
    search.textEditor.document.addDocumentListener(object : DocumentAdapter() { override fun textChanged(event: DocumentEvent) = filter() })
    init(); filter()
  }
}
