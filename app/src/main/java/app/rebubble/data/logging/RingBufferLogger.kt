package app.rebubble.data.logging

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide ring buffer of diagnostic lines for Settings → Export logs.
 * Keeps the last [capacity] timestamped entries; thread-safe under [lock].
 */
@Singleton
class RingBufferLogger(private val capacity: Int) {
    @Inject
    constructor() : this(DEFAULT_CAPACITY)

    init {
        require(capacity > 0)
    }

    private val lock = Any()
    private val entries = ArrayDeque<String>(capacity.coerceAtMost(DEFAULT_CAPACITY))

    fun log(tag: String, message: String) {
        val line = "${timestamp()} [$tag] $message"
        synchronized(lock) {
            while (entries.size >= capacity) {
                entries.removeFirst()
            }
            entries.addLast(line)
        }
    }

    fun snapshot(): List<String> = synchronized(lock) {
        entries.toList()
    }

    private fun timestamp(): String = FORMATTER.get()!!.format(Date())

    companion object {
        const val DEFAULT_CAPACITY = 500

        private val FORMATTER = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat =
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
        }
    }
}
