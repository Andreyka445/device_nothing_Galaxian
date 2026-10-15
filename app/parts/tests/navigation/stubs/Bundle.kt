// SPDX-License-Identifier: Apache-2.0
package android.os
class Bundle {
    private val strings = mutableMapOf<String, String?>()
    fun putString(key: String, value: String?) { strings[key] = value }
    fun getString(key: String): String? = strings[key]
}
object UserHandle {
    const val USER_SYSTEM = 0
    var currentUser = USER_SYSTEM
    fun myUserId(): Int = currentUser
}
