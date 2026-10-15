// SPDX-License-Identifier: Apache-2.0
package androidx.compose.runtime
import kotlin.reflect.KProperty
class MutableState<T>(var value: T)
fun <T> mutableStateOf(value: T) = MutableState(value)
operator fun <T> MutableState<T>.getValue(receiver: Any?, property: KProperty<*>): T = value
operator fun <T> MutableState<T>.setValue(receiver: Any?, property: KProperty<*>, value: T) { this.value = value }
