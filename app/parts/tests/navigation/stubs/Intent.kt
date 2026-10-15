// SPDX-License-Identifier: Apache-2.0
package android.content

/** Only the Intent contract used by the production Activity, with defensive copies. */
class Intent() {
    var action: String? = null
    var data: String? = null
    var flags: Int = 0
    var destination: Class<*>? = null
    private val extras = mutableMapOf<String, String?>()
    constructor(context: Any, destination: Class<*>) : this() { this.destination = destination }
    constructor(other: Intent) : this() {
        action = other.action; data = other.data; flags = other.flags
        destination = other.destination; extras.putAll(other.extras)
    }
    fun putExtra(key: String, value: String?): Intent = apply { extras[key] = value }
    fun getStringExtra(key: String): String? = extras[key]
    fun removeExtra(key: String): Intent = apply { extras.remove(key) }
    fun hasExtra(key: String): Boolean = extras.containsKey(key)
    fun extraKeys(): Set<String> = extras.keys.toSet()
    fun addFlags(value: Int): Intent = apply { flags = flags or value }
    companion object {
        const val FLAG_ACTIVITY_CLEAR_TOP = 0x04000000
        const val FLAG_ACTIVITY_SINGLE_TOP = 0x20000000
        const val FLAG_ACTIVITY_NEW_TASK = 0x10000000
    }
}
