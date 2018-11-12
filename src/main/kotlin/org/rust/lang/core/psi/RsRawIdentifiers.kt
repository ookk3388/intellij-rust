/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.psi

import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import org.rust.lang.refactoring.isValidRustVariableIdentifier

const val RS_RAW_PREFIX = "r#"
val CAN_NOT_BE_ESCAPED = listOf("self", "super", "crate", "Self")

fun String.unescapeIdentifier(): String = removePrefix(RS_RAW_PREFIX)
fun String.escapeIdentifierIfNeeded(): String =
    if (isValidRustVariableIdentifier(this) || this in CAN_NOT_BE_ESCAPED) this else "$RS_RAW_PREFIX$this"

val PsiElement.unescapedText: String get() {
    val text = text ?: return ""
    return if (this is LeafPsiElement) text.unescapeIdentifier() else text
}
