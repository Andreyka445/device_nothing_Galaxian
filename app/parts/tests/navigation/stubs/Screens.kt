// SPDX-License-Identifier: Apache-2.0
package org.lineageos.settings.galaxian

/** Records screen input; it does not emulate Compose or its saveable-state registry. */
object ScreenProbe {
    var page: String? = null
    var navigate: ((String) -> Unit)? = null
    var back: (() -> Unit)? = null
    var reviewId: String? = null
    var consumeReview: (() -> Unit)? = null
    fun reset() { page = null; navigate = null; back = null; reviewId = null; consumeReview = null }
    fun showPage(name: String, onBack: () -> Unit) { page = name; back = onBack }
}
fun PartsHomeScreen(onNavigate: (String) -> Unit) { ScreenProbe.page = "home"; ScreenProbe.navigate = onNavigate }
object R { object string { const val parts_owner_only = 1 } }
