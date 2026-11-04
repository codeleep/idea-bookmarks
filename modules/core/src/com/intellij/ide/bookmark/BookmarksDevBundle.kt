package com.intellij.ide.bookmark

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

object BookmarksDevBundle : DynamicBundle("messages.BookmarksDevBundle") {
  @JvmStatic
  fun message(@PropertyKey(resourceBundle = "messages.BookmarksDevBundle") key: String, vararg parameters: Any): @Nls String =
    getMessage(key, *parameters)
}
