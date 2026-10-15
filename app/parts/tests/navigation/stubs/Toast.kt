// SPDX-License-Identifier: Apache-2.0
package android.widget
class Toast {
    fun show() { shown++ }
    companion object {
        const val LENGTH_LONG = 1
        var shown = 0
        fun makeText(context: Any, resource: Int, duration: Int): Toast = Toast()
    }
}
