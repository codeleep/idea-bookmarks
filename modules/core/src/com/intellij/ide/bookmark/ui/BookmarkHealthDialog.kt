package com.intellij.ide.bookmark.ui

import com.intellij.ide.bookmark.*
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBList
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import javax.swing.*
import javax.swing.event.DocumentEvent

class BookmarkHealthDialog(private val project: Project, private val manager: BookmarksManagerImpl) : DialogWrapper(project, true) {
  private val list = JBList<BookmarkHealth.Entry>()
  private val summary = JLabel("正在巡检…")
  private val onlyInvalid = JCheckBox("仅显示失效书签", true)
  private val details = JTextArea().apply { isEditable = false; lineWrap = true }
  private var snapshot: ManagerState? = null
  private var revision = -1L
  private var entries = emptyList<BookmarkHealth.Entry>()
  private var generation = 0

  private fun render() {
    val selected = list.selectedValue?.id
    val visible = entries.filter { !onlyInvalid.isSelected || it.status.invalid }
    list.setListData(visible.toTypedArray())
    if (visible.isNotEmpty()) list.selectedIndex = visible.indexOfFirst { it.id == selected }.coerceAtLeast(0)
    summary.text = "共 ${entries.size} 个书签，${entries.count { it.status.invalid }} 个失效，${entries.count { it.status == BookmarkHealth.Status.UNSUPPORTED }} 个未检查"
    list.emptyText.text = "没有符合条件的书签"
    updateSelection()
  }

  private fun updateSelection() {
    val entry = list.selectedValue
    val state = snapshot
    val record = state?.bookmarks?.firstOrNull { it.id == entry?.id }
    val groups = state?.let { BookmarkSearchIndex.fromState(it, project.basePath).entries.firstOrNull { item -> item.id == entry?.id }?.groups }.orEmpty()
    details.text = entry?.let {
      "书签 ID：${it.id}\n位置：${it.url}${if (it.line >= 0) ":${it.line + 1}" else ""}\n状态：${it.status.label}\n所属分组：${groups.joinToString("；") { group -> group.path }}\n\n原锚点（保留空白）：\n${it.expected ?: "（未提供）"}\n\n当前源码行：\n${it.actual ?: "（不可用）"}\n\n描述：\n${record?.description.orEmpty()}"
    }.orEmpty()
    details.caretPosition = 0
    setOKActionEnabled(entry?.status?.invalid == true && record?.provider == BookmarkHealth.LINE_PROVIDER)
  }

  fun refresh() {
    val token = ++generation; setOKActionEnabled(false)
    if (manager.isRestoringBookmarks()) { summary.text = "书签正在恢复，请稍后点击重新巡检"; return }
    val current = try { manager.captureForExchange() } catch (error: IllegalStateException) { summary.text = error.message; return }
    val currentRevision = manager.stateModificationCount
    summary.text = "正在巡检…"
    ReadAction.nonBlocking<List<BookmarkHealth.Entry>> { BookmarkHealth.inspect(project, current) }
      .coalesceBy(this).expireWith(disposable).finishOnUiThread(ModalityState.any()) { result ->
        if (token != generation) return@finishOnUiThread
        if (manager.isRestoringBookmarks() || currentRevision != manager.stateModificationCount) {
          summary.text = "书签或分支已变化，请重新巡检"; snapshot = null; entries = emptyList(); list.setListData(emptyArray<BookmarkHealth.Entry>())
          return@finishOnUiThread
        }
        snapshot = current; revision = currentRevision; entries = result; render()
      }.submit(AppExecutorUtil.getAppExecutorService())
  }

  override fun doOKAction() {
    val entry = list.selectedValue ?: return
    val current = snapshot ?: return
    if (revision != manager.stateModificationCount || manager.isRestoringBookmarks()) { refresh(); return }
    val record = current.bookmarks.single { it.id == entry.id }
    if (BookmarkRepairDialog(project, manager, current, revision, record).showAndGet()) refresh()
  }

  override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
    preferredSize = Dimension(JBUI.scale(900), JBUI.scale(560))
    add(JPanel(BorderLayout()).apply {
      add(summary, BorderLayout.CENTER)
      add(JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
        add(onlyInvalid); add(JButton("重新巡检").apply { addActionListener { refresh() } })
      }, BorderLayout.EAST)
    }, BorderLayout.NORTH)
    add(JSplitPane(JSplitPane.VERTICAL_SPLIT, ScrollPaneFactory.createScrollPane(list), ScrollPaneFactory.createScrollPane(details)).apply { resizeWeight = 0.55 }, BorderLayout.CENTER)
    add(JLabel("仅检查当前书签上下文；其他提供器和非本地 URL 单独标记为未检查。"), BorderLayout.SOUTH)
  }

  init {
    title = "失效书签巡检与修复"; setOKButtonText("修复所选书签…"); setCancelButtonText("关闭")
    list.selectionMode = ListSelectionModel.SINGLE_SELECTION
    list.cellRenderer = DefaultListCellRenderer().let { renderer -> ListCellRenderer { component, value, index, selected, focus ->
      renderer.getListCellRendererComponent(component, "${value.status.label}  ${value.url}${if (value.line >= 0) ":${value.line + 1}" else ""}  [${value.id}]", index, selected, focus)
    } }
    list.addListSelectionListener { if (!it.valueIsAdjusting) updateSelection() }
    onlyInvalid.addActionListener { render() }
    init(); refresh()
  }
}

private class BookmarkRepairDialog(private val project: Project, private val manager: BookmarksManagerImpl,
                                   private val snapshot: ManagerState, private val revision: Long,
                                   private val record: BookmarkState) : DialogWrapper(project, true) {
  private val target = JTextField(BookmarkHealth.expandedUrl(project, record.attributes["url"].orEmpty()))
  private val line = JSpinner(SpinnerNumberModel(((record.attributes["line"]?.toIntOrNull() ?: 0) + 1).coerceAtLeast(1), 1, Int.MAX_VALUE, 1))
  private val list = JBList<BookmarkHealth.Candidate>()
  private val preview = JTextArea().apply { isEditable = false; lineWrap = true }
  private val hint = JLabel("正在查找候选位置…")
  private var generation = 0

  private fun loadCandidates() {
    val url = target.text; val token = ++generation
    list.setListData(emptyArray<BookmarkHealth.Candidate>()); setOKActionEnabled(false); preview.text = ""; hint.text = "正在查找候选位置…"
    ReadAction.nonBlocking<List<BookmarkHealth.Candidate>> { BookmarkHealth.candidates(project, record, url) }
      .coalesceBy(this).expireWith(disposable).finishOnUiThread(ModalityState.any()) { result ->
        if (token != generation) return@finishOnUiThread
        list.setListData(result.toTypedArray()); if (result.isNotEmpty()) list.selectedIndex = 0
        hint.text = "${result.size} 个候选（最多 200）；请核对文件、行号和原文，或手动预览目标行。"
      }.submit(AppExecutorUtil.getAppExecutorService())
  }

  private fun previewSelection() {
    val candidate = list.selectedValue
    val oldLine = record.attributes["line"]?.toIntOrNull() ?: -1
    preview.text = candidate?.let { "原位置：${record.attributes["url"]}${if (oldLine >= 0) ":${oldLine + 1}" else ""}\n原锚点：\n${record.attributes["lineText"] ?: "（无）"}\n\n新位置：${it.url}${if (it.line >= 0) ":${it.line + 1}" else ""}\n新锚点（保留缩进、Tab 和尾空白）：\n${it.text ?: "（文件书签）"}\n\n将保留原 ID、描述、快捷标记及全部分组引用。" }.orEmpty()
    preview.caretPosition = 0; setOKActionEnabled(candidate != null)
  }

  override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
    preferredSize = Dimension(JBUI.scale(880), JBUI.scale(600))
    add(JPanel(GridLayout(0, 1, 0, JBUI.scale(6))).apply {
      add(JPanel(BorderLayout()).apply {
        add(target, BorderLayout.CENTER)
        add(JButton("选择目标文件…").apply { addActionListener {
          FileChooser.chooseFile(FileChooserDescriptorFactory.createSingleFileOrFolderDescriptor(), project, null)?.let { target.text = it.url; loadCandidates() }
        } }, BorderLayout.EAST)
      })
      add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
        add(JButton("查找候选").apply { addActionListener { loadCandidates() } }); add(JLabel("目标行（从 1 开始）：")); add(line)
        add(JButton("预览指定位置").apply { addActionListener {
          try {
            val isFile = (record.attributes["line"]?.toIntOrNull() ?: -1) < 0
            val candidate = ReadAction.compute<BookmarkHealth.Candidate, RuntimeException> { BookmarkHealth.candidateAt(project, target.text, if (isFile) -1 else (line.value as Int) - 1) }
            generation++; list.setListData(arrayOf(candidate)); list.selectedIndex = 0; hint.text = "手动选择的位置，请核对后确认修复。"
          } catch (error: Exception) { Messages.showErrorDialog(project, error.localizedMessage.orEmpty(), title) }
        } })
      }); add(hint)
    }, BorderLayout.NORTH)
    add(JSplitPane(JSplitPane.VERTICAL_SPLIT, ScrollPaneFactory.createScrollPane(list), ScrollPaneFactory.createScrollPane(preview)).apply { resizeWeight = 0.45 }, BorderLayout.CENTER)
    add(JLabel("确认后先备份再修复；数据或候选源码变化会拒绝写入。"), BorderLayout.SOUTH)
  }

  override fun doOKAction() {
    val candidate = list.selectedValue ?: return
    try {
      val backup = ReadAction.compute<java.nio.file.Path, RuntimeException> { BookmarkHealth.repair(project, manager, snapshot, revision, record.id!!, candidate) }
      super.doOKAction()
      Messages.showInfoMessage(project, "修复已提交，备份：$backup", title)
    } catch (error: Exception) { Messages.showErrorDialog(project, error.localizedMessage ?: error.toString(), title) }
  }

  init {
    title = "预览并修复书签"; setOKButtonText("确认修复"); setOKActionEnabled(false)
    list.selectionMode = ListSelectionModel.SINGLE_SELECTION
    list.cellRenderer = DefaultListCellRenderer().let { renderer -> ListCellRenderer { component, value, index, selected, focus ->
      renderer.getListCellRendererComponent(component, "${value.reason}  ${value.url}${if (value.line >= 0) ":${value.line + 1}" else ""}  ${value.text.orEmpty().replace("\t", "⇥")}", index, selected, focus)
    } }
    list.addListSelectionListener { if (!it.valueIsAdjusting) previewSelection() }
    target.document.addDocumentListener(object : DocumentAdapter() {
      override fun textChanged(event: DocumentEvent) {
        generation++; list.setListData(emptyArray<BookmarkHealth.Candidate>()); preview.text = ""; setOKActionEnabled(false)
        hint.text = "目标已变化，请查找候选或预览指定位置。"
      }
    })
    line.isEnabled = (record.attributes["line"]?.toIntOrNull() ?: -1) >= 0
    line.addChangeListener { list.clearSelection(); hint.text = "行号已变化，请预览指定位置，或重新选择候选。" }
    init(); loadCandidates()
  }
}
