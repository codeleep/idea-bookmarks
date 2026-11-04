package com.intellij.ide.bookmark.ui

import com.intellij.ide.bookmark.BookmarksDevBundle.message
import com.intellij.ide.bookmark.MarkdownDescriptions
import com.intellij.ide.bookmark.ui.tree.BookmarkNode
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.project.Project
import com.intellij.ui.ScrollPaneFactory.createScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel

internal class MarkdownDescriptionPanel(private val project: Project) : JPanel(BorderLayout()) {
  private val preview = markdownPane()
  private val edit = JButton(message("description.edit"))
  private var node: BookmarkNode<*>? = null

  fun showBookmark(selected: AbstractTreeNode<*>?) {
    node = selected as? BookmarkNode<*>
    val group = node?.bookmarkGroup
    edit.isEnabled = group != null
    val text = node?.let { group?.getDescription(it.value) } ?: message("description.empty")
    preview.text = MarkdownDescriptions.toHtml(text)
    preview.caretPosition = 0
  }

  init {
    border = JBUI.Borders.emptyTop(4)
    val header = JPanel(BorderLayout()).apply {
      border = JBUI.Borders.empty(0, 6, 4, 4)
      add(JLabel(message("description.title")), BorderLayout.WEST)
      add(edit, BorderLayout.EAST)
    }
    add(header, BorderLayout.NORTH)
    add(createScrollPane(preview), BorderLayout.CENTER)
    edit.addActionListener {
      val selected = node ?: return@addActionListener
      val group = selected.bookmarkGroup ?: return@addActionListener
      MarkdownDescriptionEditor.getInstance(project).open(group, selected.value)
    }
    showBookmark(null)
  }
}
