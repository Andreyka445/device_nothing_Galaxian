// SPDX-License-Identifier: Apache-2.0
package androidx.activity

import android.content.Intent
import android.os.Bundle

/** Lifecycle entry points execute the actual overridden Activity methods. */
open class ComponentActivity {
    private var currentIntent = Intent()
    val intent: Intent get() = currentIntent
    val launched = mutableListOf<Intent>()
    var finishCount = 0
    var edgeToEdgeEnabled = false
    var content: (() -> Unit)? = null
    protected open fun onCreate(savedInstanceState: Bundle?) {}
    protected open fun onNewIntent(intent: Intent) {}
    protected open fun onSaveInstanceState(outState: Bundle) {}
    protected open fun onPause() {}
    protected open fun onResume() {}
    fun setIntent(intent: Intent) { currentIntent = intent }
    fun startActivity(intent: Intent) {
        launched += Intent(intent)
    }
    fun finish() { finishCount++ }
    fun performCreate(savedState: Bundle? = null) { onCreate(savedState) }
    fun performNewIntent(intent: Intent) { onNewIntent(intent) }
    fun performSave(): Bundle = Bundle().also { onSaveInstanceState(it) }
    fun performPause() { onPause() }
    fun performResume() { onResume() }
    fun render() { content?.invoke() }
}
fun ComponentActivity.enableEdgeToEdge() { edgeToEdgeEnabled = true }
