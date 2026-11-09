package com.intellij.ide.bookmark

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object BookmarkLinks {
  @JvmStatic fun url(id: String): String = "bookmark://" + URLEncoder.encode(id, StandardCharsets.UTF_8).replace("+", "%20")
  @JvmStatic fun decode(url: String): String? = if (!url.startsWith("bookmark://") || url.length > 20000) null else
    runCatching { URLDecoder.decode(url.removePrefix("bookmark://"), StandardCharsets.UTF_8).takeIf { it.isNotBlank() } }.getOrNull()
  @JvmStatic fun remap(markdown: String, ids: Map<String, String>): String = Regex("bookmark://[^\\s)<>\"\\]]+").replace(markdown) { match ->
    decode(match.value)?.let { ids[it] }?.let(::url) ?: match.value
  }
}
