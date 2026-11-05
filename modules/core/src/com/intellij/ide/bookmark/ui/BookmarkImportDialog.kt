package com.intellij.ide.bookmark.ui

import com.intellij.ide.bookmark.BookmarkExchange
import com.intellij.ide.bookmark.BookmarksDevBundle.message
import com.intellij.ide.bookmark.BookmarksManagerImpl
import com.intellij.ide.bookmark.ManagerState
import com.intellij.ide.bookmark.BookmarkProvider
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ScrollPaneFactory
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.JTextArea

/** All changes are computed on copies; cancel never mutates the live manager. */
class BookmarkImportDialog(private val project: Project, private val manager: BookmarksManagerImpl,
                           private val incoming: ManagerState, filename: String) : DialogWrapper(project, true) {
  private val current = manager.captureForExchange()
  private val revision = manager.stateModificationCount
  private val mode = ComboBox(arrayOf(message("exchange.merge"), message("exchange.replace")))
  private val conflict = ComboBox(arrayOf(message("exchange.keep"), message("exchange.prefer")))
  private val summary = JLabel()
  private val changes = area()
  private val before = area()
  private val after = area()
  private val diff = DiffManager.getInstance().createRequestPanel(project, disposable, null)
  private var plan = BookmarkExchange.plan(current, incoming, false, false)
  private val notices = buildList {
    val providers = BookmarkProvider.EP.getExtensions(project).map { it.javaClass.name }.toSet()
    val unknown = incoming.bookmarks.count { it.provider !in providers }
    if (unknown > 0) add("$unknown 个书签的提供器暂不可用：数据会保留，可能无法导航。")
    val base = java.nio.file.Path.of(project.basePath!!).toAbsolutePath().normalize()
    val paths = incoming.bookmarks.mapNotNull { it.attributes["url"]?.takeIf { url -> url.startsWith("file://") }?.removePrefix("file://") }
    val external = paths.count { runCatching { !java.nio.file.Path.of(it).normalize().startsWith(base) }.getOrDefault(true) }
    val missing = paths.count { runCatching { !java.nio.file.Files.exists(java.nio.file.Path.of(it)) }.getOrDefault(true) }
    if (external > 0) add("$external 个书签位于项目目录之外，保留原路径；跨机器使用时可能需要调整。")
    if (missing > 0) add("$missing 个书签的目标文件不存在，仍会保留记录；请检查目标项目目录。")
  }

  private fun area() = JTextArea().apply { isEditable = false; lineWrap = true; wrapStyleWord = true }
  private fun refresh() {
    plan = BookmarkExchange.plan(current, incoming, mode.selectedIndex == 1, conflict.selectedIndex == 1)
    conflict.isEnabled = mode.selectedIndex == 0
    summary.text = message("exchange.preview.summary", plan.state.groups.size, plan.state.bookmarks.size, plan.descriptionConflicts)
    changes.text = (notices + plan.changes).joinToString("\n")
    after.text = BookmarkExchange.toJson(plan.state, java.nio.file.Path.of(project.basePath!!))
    diff.setRequest(SimpleDiffRequest(message("exchange.diff"),
      DiffContentFactory.getInstance().create(before.text), DiffContentFactory.getInstance().create(after.text),
      message("exchange.before"), message("exchange.after")).apply { putUserData(DiffUserDataKeys.FORCE_READ_ONLY, true) })
    changes.caretPosition = 0; after.caretPosition = 0
    setOKButtonText(message(if (mode.selectedIndex == 1) "exchange.replace.apply" else "exchange.apply"))
  }

  override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
    preferredSize = Dimension(JBUI.scale(820), JBUI.scale(580))
    add(JPanel(GridLayout(0, 1, 0, JBUI.scale(6))).apply {
      add(JLabel(message("exchange.preview.hint")))
      add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply { add(JLabel(message("exchange.mode")), BorderLayout.WEST); add(mode) })
      add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply { add(JLabel(message("exchange.conflict")), BorderLayout.WEST); add(conflict) })
      add(summary)
    }, BorderLayout.NORTH)
    add(JTabbedPane().apply {
      addTab(message("exchange.changes"), ScrollPaneFactory.createScrollPane(changes))
      addTab(message("exchange.diff"), diff.component)
    }, BorderLayout.CENTER)
    add(JLabel(message("exchange.backup.hint")), BorderLayout.SOUTH)
  }

  override fun doOKAction() {
    try {
      val backup = manager.applyExchange(current, revision, plan.state)
      super.doOKAction()
      Messages.showInfoMessage(project, message("exchange.import.done", backup.toString()), message("exchange.import.title"))
    }
    catch (error: Exception) { Messages.showErrorDialog(project, error.localizedMessage ?: error.toString(), message("exchange.import.title")) }
  }

  init {
    title = message("exchange.preview.title", filename)
    before.text = BookmarkExchange.toJson(current, java.nio.file.Path.of(project.basePath!!)); before.caretPosition = 0
    mode.addActionListener { refresh() }; conflict.addActionListener { refresh() }
    init(); refresh()
  }
}
