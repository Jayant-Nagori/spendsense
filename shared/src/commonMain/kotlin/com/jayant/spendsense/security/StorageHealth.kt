package com.jayant.spendsense.security

/** The database's key exists as a wrapped blob but can't be unwrapped (Keystore key gone), or is missing for an existing database. */
class DbKeyLostException(cause: Throwable? = null) : IllegalStateException("database key can't be unwrapped", cause)

/**
 * Whether the encrypted database can be opened. Checked once before the main UI; anything but [State.OK] shows the
 * recovery screen instead of crashing in a loop. Nothing here ever deletes data.
 */
object StorageHealth {
    enum class State { OK, KEY_LOST, UNREADABLE }

    /** Runs [open] (which opens the database) and classifies a failure. Never throws. */
    fun check(open: () -> Unit): State = try {
        open(); State.OK
    } catch (e: IntegrityException) {
        throw e
    } catch (e: Throwable) {
        if (generateSequence(e) { it.cause }.any { it is DbKeyLostException }) State.KEY_LOST else State.UNREADABLE
    }
}
