// SPDX-License-Identifier: Apache-2.0
package androidx.activity.compose
import androidx.activity.ComponentActivity
fun ComponentActivity.setContent(content: () -> Unit) { this.content = content }
