package com.intellij.ide.bookmark.ui

import com.intellij.ide.bookmark.*
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridLayout
import javax.swing.*

class BookmarkSourceResultsDialog(private val project: Project, private val manager: BookmarksManagerImpl,
                                 private val selection: BookmarkSourceNavigation.Selection) : DialogWrapper(project, true) {
  private val expected = manager.captureForExchange()
  private val revision = manager.stateModificationCount
  private val choices = groupChoices(expected)
  private val group = ComboBox(choices.toTypedArray())
  private val create = JCheckBox("新建根分组", choices.isEmpty())
  private val groupName = JTextField("源码阅读")
  private val list = JBList<BookmarkSourceNavigation.Point>().apply { setListData(selection.points.toTypedArray()) }
  private fun changed() { group.isEnabled = !create.isSelected; groupName.isEnabled = create.isSelected; setOKActionEnabled(!list.isSelectionEmpty) }
  override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
    preferredSize = Dimension(JBUI.scale(760), JBUI.scale(400))
    add(JPanel(GridLayout(0, 1, 0, JBUI.scale(6))).apply {
      add(JLabel("选择要保留的位置，再加入目标分组；该分组的阅读路线会自动包含这些书签。"))
      add(group); add(JPanel(BorderLayout()).apply { add(create, BorderLayout.WEST); add(groupName, BorderLayout.CENTER) })
    }, BorderLayout.NORTH)
    add(ScrollPaneFactory.createScrollPane(list), BorderLayout.CENTER)
    add(JLabel("可多选；同一行复用已有书签和共享描述。${if (selection.skipped > 0) "已跳过 ${selection.skipped} 个非本地源码位置。" else ""}"), BorderLayout.SOUTH)
  }
  override fun doOKAction() {
    try {
      val result = ReadAction.compute<BookmarkSourceNavigation.Addition, RuntimeException> {
        BookmarkSourceNavigation.add(project, manager, expected, revision, list.selectedValuesList,
          (group.selectedItem as? BookmarkChoice)?.id, groupName.text.takeIf { create.isSelected })
      }
      super.doOKAction()
      Messages.showInfoMessage(project, "新增 ${result.created} 条书签，加入 ${result.references} 个分组引用。${if (result.backup != null) "修改前已备份。" else "所选位置已在目标分组中。"}", "加入书签")
    } catch (error: Exception) { Messages.showErrorDialog(project, error.localizedMessage.orEmpty(), "加入书签") }
  }
  init {
    title = "加入书签／阅读路线"; setOKButtonText("加入分组")
    list.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
    if (selection.points.isNotEmpty()) list.setSelectionInterval(0, selection.points.lastIndex)
    val default = expected.groups.firstOrNull { it.isDefault }?.id
    choices.indexOfFirst { it.id == default }.takeIf { it >= 0 }?.let { group.selectedIndex = it }
    create.addActionListener { changed() }; list.addListSelectionListener { changed() }
    init(); changed()
  }
}
