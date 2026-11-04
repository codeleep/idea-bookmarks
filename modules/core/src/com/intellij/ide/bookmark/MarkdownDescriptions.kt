package com.intellij.ide.bookmark

import com.intellij.util.ui.UIUtil
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist
import java.awt.Color

/** Rendering is local; embedded HTML and images cannot execute or fetch content. */
object MarkdownDescriptions {
  @JvmStatic
  fun renderBody(markdown: String): String {
    val flavour = GFMFlavourDescriptor()
    val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(markdown)
    val html = HtmlGenerator(markdown, tree, flavour).generateHtml()
    return Jsoup.clean(html, Safelist.relaxed().removeTags("img").addTags("del", "s"))
  }

  @JvmStatic
  fun summary(markdown: String): String = markdown.lineSequence()
    .map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("```") && !it.startsWith("~~~") }.orEmpty()
    .replace(Regex("^#{1,6}\\s+|^>\\s*|^[-*+]\\s+"), "")
    .replace(Regex("!?\\[([^]]*)]\\([^)]*\\)"), "$1")
    .replace(Regex("\\*\\*(.*?)\\*\\*|__(.*?)__")) { it.groupValues[1].ifEmpty { it.groupValues[2] } }
    .replace("`", "")
    .take(140)

  @JvmStatic
  fun toHtml(markdown: String): String {
    fun Color.css() = "#%06x".format(rgb and 0xffffff)
    val foreground = UIUtil.getLabelForeground().css()
    val background = UIUtil.getTextFieldBackground().css()
    val code = UIUtil.getPanelBackground().css()
    val family = UIUtil.getLabelFont().family.replace("'", "")
    return """<html><head><style>
      body { font-family: '$family'; color: $foreground; background: $background; margin: 10px; }
      pre, code { font-family: monospace; background: $code; }
      pre { padding: 8px; } blockquote { margin-left: 12px; padding-left: 8px; }
      table { border-collapse: collapse; } td, th { border: 1px solid #888888; padding: 4px; }
      a { color: #589df6; } h1 { font-size: 1.6em; } h2 { font-size: 1.35em; }
    </style></head><body>${renderBody(markdown)}</body></html>"""
  }
}
