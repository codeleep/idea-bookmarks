package com.intellij.ide.bookmark.ui

import com.intellij.ide.bookmark.Bookmark
import com.intellij.ide.bookmark.BookmarkGroup
import com.intellij.ide.bookmark.BookmarksDevBundle.message
import com.intellij.ide.bookmark.BookmarksManagerImpl
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.CheckboxTree
import com.intellij.ui.CheckboxTreeBase.CheckPolicy
import com.intellij.ui.CheckedTreeNode
import com.intellij.ui.ScrollPaneFactory.createScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultTreeModel

/** Checks are independent: selecting a parent does not select its children. */
class BookmarkGroupsTree(manager: BookmarksManagerImpl, bookmark: Bookmark) : CheckboxTree(
  object : CheckboxTreeCellRenderer(true, false) {
    override fun customizeRenderer(tree: JTree, value: Any, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
      ((value as? CheckedTreeNode)?.userObject as? BookmarkGroup)?.let { textRenderer.append(it.name) }
    }
  }, CheckedTreeNode(null), CheckPolicy(false, false, false, false, true)
) {
  val groupNodes = linkedMapOf<BookmarkGroup, CheckedTreeNode>()
  fun selectedGroups(): List<BookmarkGroup> = groupNodes.filterValues { it.isChecked }.keys.toList()
  init {
    val checked = manager.getGroups(bookmark).toSet()
    fun append(parent: CheckedTreeNode, group: BookmarkGroup?) {
      manager.getChildGroups(group).forEach {
        val child = CheckedTreeNode(it).apply { isChecked = it in checked }
        groupNodes[it] = child
        parent.add(child)
        append(child, it)
      }
    }
    append(model.root as CheckedTreeNode, null)
    (model as DefaultTreeModel).reload()
    isRootVisible = false
    showsRootHandles = true
    getAccessibleContext()?.accessibleName = message("bookmark.groups.title")
    TreeUtil.expandAll(this)
  }
}

class BookmarkGroupsDialog(project: Project, private val manager: BookmarksManagerImpl, private val bookmark: Bookmark)
  : DialogWrapper(project, true) {
  private val tree = BookmarkGroupsTree(manager, bookmark)
  override fun getPreferredFocusedComponent(): JComponent = tree
  override fun doValidate(): ValidationInfo? =
    if (tree.selectedGroups().isEmpty()) ValidationInfo(message("bookmark.groups.required"), tree) else null
  override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
    preferredSize = Dimension(JBUI.scale(460), JBUI.scale(380))
    add(JLabel(message("bookmark.groups.hint")), BorderLayout.NORTH)
    add(createScrollPane(tree), BorderLayout.CENTER)
  }
  fun showAndUpdate(): Boolean = showAndGet() && manager.setBookmarkGroups(bookmark, tree.selectedGroups())
  init {
    title = message("bookmark.groups.title")
    setOKButtonText(message("bookmark.groups.save"))
    init()
  }
}
