// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.bookmark.ui

import com.intellij.ide.bookmark.Bookmark
import com.intellij.ide.bookmark.BookmarkGroup
import com.intellij.ide.bookmark.BookmarksManager
import com.intellij.ide.bookmark.BookmarksManagerImpl
import com.intellij.ide.bookmark.ui.tree.GroupNode
import com.intellij.ide.bookmark.ui.tree.RootNode
import com.intellij.ui.tree.TreeVisitor
import com.intellij.util.ui.tree.TreeUtil
import javax.swing.tree.TreePath

internal class GroupBookmarkVisitor(val group: BookmarkGroup, val bookmark: Bookmark? = null) : TreeVisitor {
  override fun visit(path: TreePath): TreeVisitor.Action {
    val node = TreeUtil.getAbstractTreeNode(path) ?: return TreeVisitor.Action.SKIP_CHILDREN
    if (node is RootNode) return TreeVisitor.Action.CONTINUE
    if (node is GroupNode) {
      if (node.value === group && bookmark == null) return TreeVisitor.Action.INTERRUPT
      val manager = BookmarksManager.getInstance(node.project) as? BookmarksManagerImpl
      return if (node.value === group || manager?.isGroupInSubtree(group, node.value) == true) TreeVisitor.Action.CONTINUE
      else TreeVisitor.Action.SKIP_CHILDREN
    }
    if (bookmark != null && node.value == bookmark) return TreeVisitor.Action.INTERRUPT
    return TreeVisitor.Action.CONTINUE
  }
}
