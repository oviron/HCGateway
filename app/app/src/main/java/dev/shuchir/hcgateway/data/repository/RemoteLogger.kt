package dev.shuchir.hcgateway.data.repository

import dev.shuchir.hcgateway.data.remote.ApiService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

data class LogEntry(
    val ts: String,
    val level: String,
    val tag: String,
    val msg: String,
)

data class LogRequest(val entries: List<LogEntry>)

@Singleton
class RemoteLogger @Inject constructor(
    private val apiService: ApiService,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()
    private val buffer = mutableListOf<LogEntry>()
    private val FLUSH_SIZE = 20

    fun log(level: String, tag: String, msg: String) {
        android.util.Log.d(tag, "[$level] $msg")
        scope.launch {
            val toSend: List<LogEntry>?
            mutex.withLock {
                buffer.add(LogEntry(
                    ts = Instant.now().toString(),
                    level = level,
                    tag = tag,
                    msg = msg,
                ))
                toSend = if (buffer.size >= FLUSH_SIZE) {
                    val copy = buffer.toList()
                    buffer.clear()
                    copy
                } else null
            }
            toSend?.let { send(it) }
        }
    }

    fun i(tag: String, msg: String) = log("INFO", tag, msg)
    fun w(tag: String, msg: String) = log("WARN", tag, msg)
    fun e(tag: String, msg: String) = log("ERROR", tag, msg)
    fun d(tag: String, msg: String) = log("DEBUG", tag, msg)

    fun flush() {
        scope.launch {
            val toSend: List<LogEntry>
            mutex.withLock {
                toSend = buffer.toList()
                buffer.clear()
            }
            if (toSend.isNotEmpty()) send(toSend)
        }
    }

    private suspend fun send(entries: List<LogEntry>) {
        try {
            apiService.sendLogs(LogRequest(entries))
        } catch (_: Exception) {
            // Best-effort — don't crash if server unreachable
        }
    }
}
