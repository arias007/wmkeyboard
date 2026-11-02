package com.wasimaster.wmkeyboard.core.history

import android.content.Context
import com.wasimaster.wmkeyboard.core.util.SnapshotFile
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One committed string, as it is written down. */
@Serializable
data class TypedEntry(
    /** Epoch milliseconds, passed in by the caller so tests own their clock. */
    val t: Long = 0,
    val s: String = "",
    /** The package the text went into: the same words read differently in a chat and a search box. */
    val a: String = "",
)

@Serializable
private data class StoredHistory(
    val enabled: Boolean = false,
    val expiryHours: Int = TypingHistoryStore.DEFAULT_EXPIRY_HOURS,
    val entries: List<TypedEntry> = emptyList(),
)

/**
 * What was typed into the field, kept on the phone (#1073).
 *
 * Committed text, not keystrokes: an entry is written once a word or phrase
 * ends — a space, a pick, Enter — so a composing buffer never reaches here.
 * `nihao` is not something anyone typed; 你好 is. That also keeps the write
 * rate at one line a word rather than one a key, and keeps backspace noise out
 * of the file.
 *
 * The switch and the record live in one file on purpose: [KeyboardSettings] is
 * already close to the JVM's `copy$default` ceiling (the reason `NotesSettings`
 * gives), and a feature turned on once should not push the settings object over
 * it.
 *
 * Nothing here is sent anywhere, and it is not in sync. It travels only in a
 * backup the user asked for, which is the whole point of having it.
 */
class TypingHistoryStore(
    private val file: File?,
    /** Newest entries kept; older ones fall off the end. */
    var maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {

    data class Snapshot(
        val enabled: Boolean = false,
        val expiryHours: Int = DEFAULT_EXPIRY_HOURS,
        val entries: List<TypedEntry> = emptyList(),
    )

    @Volatile
    private var cache: Snapshot? = null

    @Volatile
    private var landed = 0L

    private val snapshotFile = file?.let(::SnapshotFile)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** When the file was last read, so a write from outside — a restore — is noticed. */
    @Volatile
    private var readAt = 0L

    /**
     * Everything as it stands, from the file the first time and memory after.
     *
     * The file's own timestamp is the signal for a write from outside this
     * object: restoring a backup puts a whole new document there, and the
     * keyboard must not keep serving the one it had cached.
     */
    fun snapshot(): Snapshot {
        cache?.let { held ->
            val stamp = file?.lastModified() ?: 0L
            if (stamp == readAt) return held
        }
        return readFromDisk().also {
            readAt = file?.lastModified() ?: 0L
            cache = it
        }
    }

    val enabled: Boolean get() = snapshot().enabled

    fun setEnabled(value: Boolean) = edit { it.copy(enabled = value) }

    fun setExpiryHours(hours: Int) = edit {
        it.copy(expiryHours = hours.coerceIn(MIN_EXPIRY_HOURS, MAX_EXPIRY_HOURS))
    }

    /**
     * One committed string. Refused when it is empty, longer than
     * [MAX_ENTRY_CHARS] — that is a paste, not typing — or a bare one-time
     * code, which is a secret whatever the field said it was.
     */
    fun record(text: String, app: String, at: Long) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_ENTRY_CHARS) return
        // A bare code alone, with a digit in it: the shape a one-time password
        // has, and never the shape prose has.
        if (trimmed.none { it.isWhitespace() } &&
            trimmed.any { it.isDigit() } &&
            LOOKS_LIKE_CODE.matches(trimmed)
        ) {
            return
        }
        edit { current ->
            current.copy(entries = current.entries + TypedEntry(at, trimmed, app))
        }
    }

    fun clear() = edit { it.copy(entries = emptyList()) }

    /** Drops what has aged out, without adding anything. */
    fun trim(now: Long) {
        val current = snapshot()
        if (current.expiryHours <= 0) return
        val cutoff = now - current.expiryHours * HOUR_MS
        if (current.entries.none { it.t < cutoff }) return
        edit { it.copy(entries = it.entries.filter { entry -> entry.t >= cutoff }) }
    }

    private fun edit(change: (Snapshot) -> Snapshot) {
        val before = snapshot()
        var after = change(before)
        if (after.expiryHours > 0) {
            val cutoff = System.currentTimeMillis() - after.expiryHours * HOUR_MS
            after = after.copy(entries = after.entries.filter { it.t >= cutoff })
        }
        after = after.copy(entries = after.entries.takeLast(maxEntries.coerceAtLeast(1)))
        cache = after
        write(after)
    }

    private fun write(value: Snapshot) {
        val target = file ?: return
        val text = json.encodeToString(
            StoredHistory.serializer(),
            StoredHistory(value.enabled, value.expiryHours, value.entries),
        )
        // A save split across a process kill is the one failure worth
        // surviving: the file is written whole and renamed over, so the
        // previous one stays readable.
        runCatching {
            val ticket = (landed + 1).also { landed = it }
            val throughSnapshot = snapshotFile?.write(ticket) { text }
            if (throughSnapshot == null) SnapshotFile.writeAtomically(target, text)
        }
        // Written here, so the timestamp check does not read it straight back.
        readAt = target.lastModified()
    }

    private fun readFromDisk(): Snapshot {
        val target = file ?: return Snapshot()
        val text = runCatching { if (target.exists()) target.readText() else null }.getOrNull()
            ?: return Snapshot()
        val stored = runCatching { json.decodeFromString(StoredHistory.serializer(), text) }.getOrNull()
            ?: return Snapshot()
        return Snapshot(stored.enabled, stored.expiryHours, stored.entries)
    }

    companion object {
        /** The file inside the app's own storage, as the backup section names it. */
        const val FILE_PATH = "history/typed.json"

        const val DEFAULT_MAX_ENTRIES = 5_000

        /** A week: long enough to be useful, short enough that forgetting it is survivable. */
        const val DEFAULT_EXPIRY_HOURS = 168

        const val MIN_EXPIRY_HOURS = 1
        const val MAX_EXPIRY_HOURS = 24 * 365

        /** One entry is a word, a phrase, a sentence. A whole document is a paste, not typing. */
        const val MAX_ENTRY_CHARS = 500

        private const val HOUR_MS = 3_600_000L

        /** A bare code alone: the shape a one-time password has, never prose. */
        private val LOOKS_LIKE_CODE = Regex("""[A-Z0-9]{4,8}""")
    }
}

/**
 * The one store the process shares. The keyboard service writes it and the
 * settings screen reads it, and both are in the same process, so a single
 * instance keeps the switch from being a file read on every keystroke.
 */
object TypingHistory {

    @Volatile
    private var store: TypingHistoryStore? = null

    fun shared(context: Context): TypingHistoryStore {
        store?.let { return it }
        return synchronized(this) {
            store ?: TypingHistoryStore(
                File(context.filesDir, TypingHistoryStore.FILE_PATH),
            ).also { store = it }
        }
    }
}
