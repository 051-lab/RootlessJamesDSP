package me.timschneeberger.rootlessjamesdsp.service

internal class DarwinRequestGate<T>(initial: T? = null) {
    private val lock = Any()
    private var requested = initial

    fun request(value: T) = synchronized(lock) {
        requested = value
    }

    fun <R> request(value: T, action: () -> R): R = synchronized(lock) {
        requested = value
        action()
    }

    fun isCurrent(value: T): Boolean = synchronized(lock) {
        requested == value
    }

    fun restoreIfCurrent(failed: T, fallback: T?): Boolean = synchronized(lock) {
        if (requested != failed) return@synchronized false
        requested = fallback
        true
    }

    fun runIfCurrent(value: T, action: () -> Unit): Boolean = synchronized(lock) {
        if (requested != value) return@synchronized false
        action()
        true
    }
}
