package com.intellij.ide.bookmark.ui

import com.intellij.ide.bookmark.*
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBList
import com.intellij.util.SingleAlarm
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.*

class BookmarkImpactPanel(private val project: Project, private val manager: BookmarksManagerImpl,
                          private val changed: (List<BookmarkImpact.Entry>) -> Unit) : JPanel(BorderLayout()), Disposable {
  private val store = BookmarkWorkspaceStore.getInstance(project)
  private val list = JBList<BookmarkImpact.Entry>()
  private val search = SearchTextField(false)
  private val status = ComboBox(arrayOf("全部书签", "仅待复核", "未建立基线"))
  private val summary = JLabel()
  private val details = JTextArea().apply { isEditable = false; lineWrap = true }
  private val review = JButton("建立基线")
  private val diff = JButton("查看差异")
  private val clear = JButton("清除所选基线")
  private val open = JButton("打开代码")
  private var state: ManagerState? = null
  private var reading = BookmarkReadingState()
  private var revision = -1L
  private var reviewRevision = -1L
  private var generation = 0
  private var entries = emptyList<BookmarkImpact.Entry>()
  private var labels = emptyMap<String, BookmarkSearchIndex.Entry>()
  @Volatile private var watched = emptySet<String>()
  private val alarm = SingleAlarm({ refresh() }, 300, this, ModalityState.any())

  fun selectBookmark(id: String?) { refresh(id) }
  private fun refresh(preferred: String? = list.selectedValue?.id) {
    val token = ++generation; review.isEnabled = false; diff.isEnabled = false; clear.isEnabled = false
    if (manager.isRestoringBookmarks()) { summary.text = "书签正在恢复…"; return }
    val before = manager.stateModificationCount
    val snapshot = try { manager.captureForExchange() } catch (error: Exception) {
      state = null; entries = emptyList(); list.setListData(emptyArray()); details.text = ""
      summary.text = "无法检查书签：${error.localizedMessage.orEmpty()}"; changed(emptyList()); return
    }
    val baselineRevision = store.reviewRevision(); val baseline = store.readingState()
    watched = snapshot.bookmarks.mapNotNull { it.attributes["url"]?.let { url -> BookmarkHealth.expandedUrl(project, url) } }.toSet()
    summary.text = "正在检查源码变更…"
    ReadAction.nonBlocking<List<BookmarkImpact.Entry>> { BookmarkImpact.inspect(project, snapshot, baseline) }
      .coalesceBy(this).expireWith(this).finishOnUiThread(ModalityState.any()) { result ->
        if (token != generation) return@finishOnUiThread
        if (before != manager.stateModificationCount || baselineRevision != store.reviewRevision() || manager.isRestoringBookmarks()) { alarm.cancelAndRequest(); return@finishOnUiThread }
        state = snapshot; reading = baseline; revision = before; reviewRevision = baselineRevision
        entries = result; labels = BookmarkSearchIndex.fromState(snapshot, project.basePath).entries.associateBy { it.id }
        render(preferred); changed(result)
      }.submit(AppExecutorUtil.getAppExecutorService())
  }

  private fun render(preferred: String? = list.selectedValue?.id) {
    val ids = state?.let { BookmarkSearchIndex.fromState(it, project.basePath).search(search.text).map { entry -> entry.id }.toSet() }.orEmpty()
    val shown = entries.filter { it.id in ids && when (status.selectedIndex) {
      1 -> it.status in setOf(BookmarkImpact.Status.CHANGED, BookmarkImpact.Status.MOVED)
      2 -> it.status == BookmarkImpact.Status.UNREVIEWED
      else -> true
    } }
    list.setListData(shown.toTypedArray())
    if (shown.isNotEmpty()) list.selectedIndex = shown.indexOfFirst { it.id == preferred }.coerceAtLeast(0)
    summary.text = "待复核 ${entries.count { it.status in setOf(BookmarkImpact.Status.CHANGED, BookmarkImpact.Status.MOVED) }} · 未建基线 ${entries.count { it.status == BookmarkImpact.Status.UNREVIEWED }} · 显示 ${shown.size}"
    list.emptyText.text = "没有符合条件的书签"; selectionChanged()
  }

  private fun current(): Boolean = !manager.isRestoringBookmarks() && revision == manager.stateModificationCount && reviewRevision == store.reviewRevision()
  private fun selectionChanged() {
    val selected = list.selectedValuesList
    review.isEnabled = current() && selected.isNotEmpty() && selected.all { it.status !in setOf(BookmarkImpact.Status.MISSING, BookmarkImpact.Status.UNSUPPORTED, BookmarkImpact.Status.TOO_LARGE) }
    diff.isEnabled = current() && selected.size == 1 && reading.reviews.any { it.bookmarkId == selected.single().id }
    clear.isEnabled = current() && selected.any { entry -> reading.reviews.any { it.bookmarkId == entry.id } }
    open.isEnabled = current() && selected.size == 1
    review.text = if (selected.any { entry -> reading.reviews.any { it.bookmarkId == entry.id } }) "确认已复核" else "建立基线"
    details.text = selected.singleOrNull()?.let { entry ->
      val record = state?.bookmarks?.firstOrNull { it.id == entry.id }
      "${entry.status.label}\n${labels[entry.id]?.location.orEmpty()}\n书签 ID：${entry.id}\n${if (entry.descriptionChanged) "描述也已修改，请一并复核。" else ""}\n\n${record?.description.orEmpty()}"
    }.orEmpty()
    details.caretPosition = 0
  }

  private fun markReviewed() {
    val snapshot = state ?: return; if (!current()) { refresh(); return }
    val ids = list.selectedValuesList.map { it.id }.toSet()
    val expected = revision; val baselineRevision = reviewRevision
    review.isEnabled = false
    ReadAction.nonBlocking<List<BookmarkImpact.Capture>> {
      val cache = mutableMapOf<String, BookmarkImpact.Capture?>()
      snapshot.bookmarks.filter { it.id in ids }.mapNotNull { record ->
        val url = record.attributes["url"].orEmpty()
        cache.getOrPut(url) { BookmarkImpact.capture(project, record) }?.copy(bookmarkId = record.id!!)
      }
    }.coalesceBy(this, "review").expireWith(this).finishOnUiThread(ModalityState.current()) { captures ->
      try {
        check(expected == manager.stateModificationCount && !manager.isRestoringBookmarks()) { "书签或分支已变化，请刷新后复核" }
        check(captures.size == ids.size && ReadAction.compute<Boolean, RuntimeException> { BookmarkImpact.stillCurrent(project, captures) }) { "源码已变化或无法读取，请重新查看差异" }
        store.markReviewed(snapshot, captures, baselineRevision)
      } catch (error: Exception) { Messages.showErrorDialog(project, error.localizedMessage.orEmpty(), "源码复核") }
      refresh()
    }.submit(AppExecutorUtil.getAppExecutorService())
  }

  private fun showDiff() {
    if (!current()) { refresh(); return }
    val entry = list.selectedValue ?: return
    val record = state?.bookmarks?.firstOrNull { it.id == entry.id } ?: return
    val baseline = reading.reviews.firstOrNull { it.bookmarkId == entry.id } ?: return
    val before = reading.sources.firstOrNull { it.id == baseline.snapshotId }?.text ?: return
    val expectedRevision = revision; val expectedReviewRevision = reviewRevision
    ReadAction.nonBlocking<BookmarkImpact.Capture?> { BookmarkImpact.capture(project, record) }.expireWith(this)
      .finishOnUiThread(ModalityState.current()) { source ->
        if (expectedRevision != manager.stateModificationCount || expectedReviewRevision != store.reviewRevision() || manager.isRestoringBookmarks()) { refresh(); return@finishOnUiThread }
        val request = SimpleDiffRequest("书签源码变更：${labels[entry.id]?.location.orEmpty()}",
          DiffContentFactory.getInstance().create(before), DiffContentFactory.getInstance().create(source?.text ?: "（文件无法读取）"), "已复核版本", "当前编辑内容")
        request.putUserData(DiffUserDataKeys.FORCE_READ_ONLY, true)
        DiffManager.getInstance().showDiff(project, request)
      }.submit(AppExecutorUtil.getAppExecutorService())
  }

  override fun dispose() = Unit
  internal fun toolWindowMenu() = JPopupMenu().apply {
    add(BookmarkWorkbenchUi.item(open)); addSeparator(); add(BookmarkWorkbenchUi.item(clear))
  }
  init {
    border = JBUI.Borders.empty(6)
    add(JPanel(BorderLayout(0, JBUI.scale(6))).apply {
      add(BookmarkWorkbenchUi.heading(status, BookmarkWorkbenchUi.refreshButton { refresh() }), BorderLayout.NORTH)
      add(search, BorderLayout.CENTER)
      add(summary, BorderLayout.SOUTH)
    }, BorderLayout.NORTH)
    add(JSplitPane(JSplitPane.VERTICAL_SPLIT, ScrollPaneFactory.createScrollPane(list), ScrollPaneFactory.createScrollPane(details)).apply { resizeWeight = 0.6 }, BorderLayout.CENTER)
    add(BookmarkWorkbenchUi.actions(diff, review), BorderLayout.SOUTH)
    review.toolTipText = "确认源码与描述后，为所选书签建立或更新复核基线；支持多选"
    diff.toolTipText = "按完整文件对比已复核版本和当前编辑内容"
    open.addActionListener { list.selectedValue?.let { BookmarkNavigation.locate(project, manager, it.id) } }
    BookmarkWorkbenchUi.installMenu(list) { toolWindowMenu() }
    list.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
    list.cellRenderer = DefaultListCellRenderer().let { renderer -> ListCellRenderer { component, entry, index, selected, focus ->
      renderer.getListCellRendererComponent(component, "${entry.status.label} · ${labels[entry.id]?.location.orEmpty()} ${labels[entry.id]?.snippet("").orEmpty()}", index, selected, focus)
    } }
    list.addListSelectionListener { if (!it.valueIsAdjusting) selectionChanged() }
    search.textEditor.document.addDocumentListener(object : DocumentAdapter() { override fun textChanged(event: javax.swing.event.DocumentEvent) = render() })
    status.addActionListener { render() }; review.addActionListener { markReviewed() }; diff.addActionListener { showDiff() }
    clear.addActionListener {
      if (current()) store.clearReviews(list.selectedValuesList.map { it.id }.toSet(), reviewRevision)
      refresh()
    }
    listenToBookmarks(project, this) { alarm.cancelAndRequest() }
    EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
      override fun documentChanged(event: DocumentEvent) {
        if (FileDocumentManager.getInstance().getFile(event.document)?.url in watched) alarm.cancelAndRequest()
      }
    }, this)
    project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
      override fun after(events: List<VFileEvent>) { if (events.any { it.file?.url in watched || "file://${it.path}" in watched }) alarm.cancelAndRequest() }
    })
    refresh()
  }
}
