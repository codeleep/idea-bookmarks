package com.intellij.ide.bookmark.ui

import com.intellij.ide.DataManager
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.GridLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

/** Secondary actions belong to the tool window's native three-dot menu. */
internal object BookmarkWorkbenchUi {
  fun refreshButton(refresh: () -> Unit) = JButton(AllIcons.Actions.Refresh).apply {
    preferredSize = JBUI.size(28, 28); minimumSize = preferredSize
    margin = JBUI.insets(4)
    toolTipText = "刷新"; accessibleContext.accessibleName = "刷新"
    addActionListener { refresh() }
  }

  fun item(button: AbstractButton) = JMenuItem(button.text, button.icon).apply {
    isEnabled = button.isEnabled
    toolTipText = button.toolTipText
    addActionListener { if (button.isEnabled) button.doClick(0) }
  }

  fun heading(scope: JComponent, refresh: JButton) = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
    add(scope, BorderLayout.CENTER)
    add(refresh, BorderLayout.EAST)
  }

  fun actions(vararg buttons: JButton) = JPanel(GridLayout(1, buttons.size, JBUI.scale(6), 0)).apply {
    border = JBUI.Borders.emptyTop(6)
    buttons.forEach { add(it) }
  }

  fun menuActions(menu: JPopupMenu): Array<AnAction> = menu.components.mapNotNull { item ->
      when (item) {
        is JSeparator -> com.intellij.openapi.actionSystem.Separator.create()
        is JMenuItem -> object : DumbAwareAction(item.text, item.toolTipText, item.icon) {
          init { templatePresentation.isEnabled = item.isEnabled }
          override fun actionPerformed(event: AnActionEvent) { if (item.isEnabled) item.doClick(0) }
        }
        else -> null
      }
    }.toTypedArray()

  private fun popup(component: JComponent, menu: JPopupMenu): com.intellij.openapi.ui.popup.ListPopup {
    val group = DefaultActionGroup(*menuActions(menu))
    return JBPopupFactory.getInstance().createActionGroupPopup(null, group,
      DataManager.getInstance().getDataContext(component), JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true)
  }

  fun installMenu(list: JList<*>, menu: () -> JPopupMenu) {
    list.addMouseListener(object : MouseAdapter() {
      private fun show(event: MouseEvent) {
        if (!event.isPopupTrigger) return
        val row = list.locationToIndex(event.point)
        if (row >= 0 && list.getCellBounds(row, row).contains(event.point) && !list.isSelectedIndex(row)) list.selectedIndex = row
        popup(list, menu()).show(RelativePoint(list, event.point))
      }
      override fun mousePressed(event: MouseEvent) = show(event)
      override fun mouseReleased(event: MouseEvent) = show(event)
    })
  }
}
