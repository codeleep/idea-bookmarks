package com.intellij.ide.bookmark.ui

import com.intellij.ide.bookmark.BookmarkGroup
import com.intellij.ide.bookmark.BookmarksDevBundle.message
import com.intellij.ide.bookmark.BookmarksManagerImpl
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.ScrollPaneFactory.createScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.TreePath

class GroupParentDialog(project: Project?, parent: Component?, private val manager: BookmarksManagerImpl, private val group: BookmarkGroup)
  : DialogWrapper(project, parent, true, IdeModalityType.IDE) {
  private val root = DefaultMutableTreeNode(message("group.move.root"))
  private val tree = Tree(root)
  private val candidate: BookmarkGroup?
    get() = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? BookmarkGroup

  override fun doValidate(): ValidationInfo? =
    if (tree.selectionPath != null && manager.canSetParentGroup(group, candidate)) null
    else ValidationInfo(message("group.move.invalid"), tree)

  override fun getPreferredFocusedComponent(): JComponent = tree
  override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
    preferredSize = Dimension(JBUI.scale(400), JBUI.scale(330))
    add(JLabel(message("group.move.destination")), BorderLayout.NORTH)
    add(createScrollPane(tree), BorderLayout.CENTER)
  }

  fun showAndMove(): Boolean = showAndGet() && manager.setParentGroup(group, candidate)

  init {
    title = message("group.move.title", group.name)
    setOKButtonText(message("group.move.save"))
    fun append(node: DefaultMutableTreeNode, parentGroup: BookmarkGroup?) {
      manager.getChildGroups(parentGroup).filter { !manager.isGroupInSubtree(it, group) }.forEach {
        val child = DefaultMutableTreeNode(it)
        node.add(child)
        append(child, it)
      }
    }
    append(root, null)
    tree.cellRenderer = object : DefaultTreeCellRenderer() {
      override fun getTreeCellRendererComponent(tree: javax.swing.JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, focus: Boolean): Component {
        super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, focus)
        ((value as? DefaultMutableTreeNode)?.userObject as? BookmarkGroup)?.let { text = it.name }
        return this
      }
    }
    tree.accessibleContext.accessibleName = message("group.move.destination")
    val previous = manager.getParentGroup(group)
    val selected = root.depthFirstEnumeration().asSequence().filterIsInstance<DefaultMutableTreeNode>().firstOrNull { it.userObject === previous } ?: root
    tree.selectionPath = TreePath(selected.path)
    init()
    TreeUtil.expandAll(tree)
  }
}
