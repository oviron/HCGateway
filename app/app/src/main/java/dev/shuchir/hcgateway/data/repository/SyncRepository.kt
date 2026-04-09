package dev.shuchir.hcgateway.data.repository

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import dev.shuchir.hcgateway.data.local.PreferencesRepository
import dev.shuchir.hcgateway.data.remote.ApiService
import dev.shuchir.hcgateway.data.remote.SyncRequest
import dev.shuchir.hcgateway.domain.model.RECORD_TYPES
import dev.shuchir.hcgateway.domain.model.SyncState
import dev.shuchir.hcgateway.domain.model.TypeSyncResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncRepository @Inject constructor(
    private val healthConnectRepository: HealthConnectRepository,
    private val apiService: ApiService,
    private val preferencesRepository: PreferencesRepository,
    private val gson: Gson,
    private val log: RemoteLogger,
) {
    companion object {
        private const val TAG = "Sync"
        const val MIN_SYNC_DISPLAY_MS = 1000L
        private const val DEFAULT_LOOKBACK_DAYS = 29L
        private const val BATCH_PAGES = 4
    }

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private var syncJob: Job? = null
    private var currentTypeResults = mutableListOf<TypeSyncResult>()
    @Volatile private var currentRecordCount = 0

    val isSyncing: Boolean get() = syncJob?.isActive == true

    suspend fun sync(customStartDate: LocalDate? = null, customEndDate: LocalDate? = null) {
        if (isSyncing) return
        performSync(customStartDate, customEndDate)
    }

    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
        syncJob?.cancel()
        syncJob = null
        _syncState.value = SyncState.Cancelled(currentRecordCount, currentTypeResults.toList())
    }

    fun setSyncJob(job: Job) {
        syncJob = job
    }

    private fun updateSyncState(state: SyncState) {
        if (!cancelled) _syncState.value = state
    }

    private suspend fun performSync(customStartDate: LocalDate?, customEndDate: LocalDate?) {
        cancelled = false
        _syncState.value = SyncState.Syncing("", 0, RECORD_TYPES.size)
        currentTypeResults = mutableListOf()
        currentRecordCount = 0
        val typeResults = currentTypeResults
        val failedTypes = mutableListOf<String>()
        var totalRecords = 0
        val syncStartTime = System.currentTimeMillis()

        try {
            val settings = preferencesRepository.settings.first()

            if (customStartDate != null) {
                log.i(TAG, "Force sync: $customStartDate → ${customEndDate ?: LocalDate.now()}")
                val startTime = customStartDate.atStartOfDay(ZoneId.of("UTC")).toInstant()
                val endTime = (customEndDate ?: LocalDate.now()).plusDays(1)
                    .atStartOfDay(ZoneId.of("UTC")).toInstant()
                totalRecords = fullSync(startTime, endTime, typeResults, failedTypes)
            } else if (settings.fullSyncMode) {
                log.i(TAG, "Full sync (${DEFAULT_LOOKBACK_DAYS}d lookback)")
                val startTime = Instant.now().minus(java.time.Duration.ofDays(DEFAULT_LOOKBACK_DAYS))
                totalRecords = fullSync(startTime, Instant.now(), typeResults, failedTypes)
            } else if (settings.changesToken.isNotBlank()) {
                log.i(TAG, "Delta sync")
                totalRecords = deltaSync(settings.changesToken, typeResults, failedTypes)
            } else {
                log.i(TAG, "Initial full sync (no changes token)")
                val startTime = Instant.now().minus(java.time.Duration.ofDays(DEFAULT_LOOKBACK_DAYS))
                totalRecords = fullSync(startTime, Instant.now(), typeResults, failedTypes)
            }

            // Ensure minimum display time for progress animation
            val elapsed = System.currentTimeMillis() - syncStartTime
            if (elapsed < MIN_SYNC_DISPLAY_MS) {
                kotlinx.coroutines.delay(MIN_SYNC_DISPLAY_MS - elapsed)
            }

            preferencesRepository.updateLastSync(System.currentTimeMillis())
            if (typeResults.isNotEmpty()) {
                preferencesRepository.updateLastSyncResults(gson.toJson(typeResults))
            }
            val totalElapsed = System.currentTimeMillis() - syncStartTime
            log.i(TAG, "Sync done: $totalRecords records in ${totalElapsed}ms, ${failedTypes.size} failed")
            log.flush()
            _syncState.value = SyncState.Done(totalRecords, typeResults, failedTypes)
        } catch (e: CancellationException) {
            log.i(TAG, "Sync cancelled")
            log.flush()
            if (typeResults.isNotEmpty()) {
                preferencesRepository.updateLastSyncResults(gson.toJson(typeResults))
            }
            throw e
        } catch (e: Exception) {
            log.e(TAG, "Sync error: ${e.message}")
            _syncState.value = SyncState.Error(e.message ?: "Sync failed")
        }
    }

    private suspend fun fullSync(
        startTime: Instant,
        endTime: Instant,
        typeResults: MutableList<TypeSyncResult>,
        failedTypes: MutableList<String> = mutableListOf(),
    ): Int {
        var completedCount = 0
        var totalRecords = 0

        // Health Connect IPC is single-threaded (one Binder channel). Parallel reads
        // just queue up and burn rate-limit quota. Read types sequentially, but
        // overlap HC reads with server uploads via a producer-consumer channel.
        // Lightweight types first so useful data reaches the server ASAP;
        // heavy sample-based types (HeartRate, StepsCadence, etc.) last.
        val heavyTypes = setOf(
            "StepsCadence", "HeartRate", "Speed", "Power",
            "CyclingPedalingCadence", "SkinTemperature",
        )
        val sortedTypes = RECORD_TYPES.sortedBy { if (it.name in heavyTypes) 1 else 0 }

        for (type in sortedTypes) {
            coroutineScope {
                ensureActive()
                log.i(TAG, "Reading ${type.name}...")
                val typeStartMs = System.currentTimeMillis()
                try {
                    val channel = kotlinx.coroutines.channels.Channel<Pair<com.google.gson.JsonElement, Int>>(4)
                    var typeTotal = 0
                    var readerError: Exception? = null

                    // Producer: read pages from Health Connect (sequential IPC)
                    val reader = launch(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            healthConnectRepository.readRecordsPaged(
                                type.recordClass, startTime, endTime,
                            ) { page ->
                                coroutineContext.ensureActive()
                                val json = healthConnectRepository.recordsToJson(page)
                                typeTotal += page.size
                                channel.send(json to page.size)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            readerError = e
                        } finally {
                            channel.close()
                        }
                    }

                    // Consumer: batch pages and upload to server (overlaps with HC reads)
                    val batch = mutableListOf<Pair<JsonElement, Int>>()
                    var batchRecords = 0

                    for ((json, pageSize) in channel) {
                        ensureActive()
                        batch.add(json to pageSize)
                        batchRecords += pageSize

                        if (batch.size >= BATCH_PAGES) {
                            val merged = mergeJsonArrays(batch.map { it.first })
                            apiService.syncRecords(type.name, SyncRequest(merged))
                            totalRecords += batchRecords
                            currentRecordCount = totalRecords
                            updateSyncState(SyncState.Syncing(
                                type.name, completedCount, RECORD_TYPES.size, totalRecords,
                                completedTypes = typeResults.toList(),
                            ))
                            batch.clear()
                            batchRecords = 0
                        }
                    }
                    // Flush remaining
                    if (batch.isNotEmpty()) {
                        val merged = mergeJsonArrays(batch.map { it.first })
                        apiService.syncRecords(type.name, SyncRequest(merged))
                        totalRecords += batchRecords
                        currentRecordCount = totalRecords
                        updateSyncState(SyncState.Syncing(
                            type.name, completedCount, RECORD_TYPES.size, totalRecords,
                        ))
                    }

                    reader.join()
                    readerError?.let { throw it }

                    val typeElapsed = System.currentTimeMillis() - typeStartMs
                    if (typeTotal > 0) {
                        log.i(TAG, "${type.name}: $typeTotal records in ${typeElapsed}ms")
                        typeResults.add(TypeSyncResult(type.name, typeTotal))
                    } else {
                        log.d(TAG, "${type.name}: empty (${typeElapsed}ms)")
                    }
                } catch (e: Exception) {
                    val isUnsupported = e is SecurityException ||
                        e.cause is SecurityException ||
                        e.message?.contains("SecurityException") == true
                    if (!isUnsupported) {
                        log.e(TAG, "${type.name} failed: ${e.javaClass.simpleName}: ${e.message}")
                        failedTypes.add(type.name)
                    } else {
                        log.i(TAG, "${type.name}: skipped (unsupported)")
                    }
                }
                completedCount++
                updateSyncState(SyncState.Syncing(
                    type.name, completedCount, RECORD_TYPES.size, totalRecords,
                ))
            }
        }

        try {
            val token = healthConnectRepository.getChangesToken()
            preferencesRepository.updateChangesToken(token)
            log.i(TAG, "Changes token saved")
        } catch (e: Exception) {
            log.e(TAG, "Failed to save changes token: ${e.message}")
        }

        return totalRecords
    }

    private suspend fun deltaSync(
        changesToken: String,
        typeResults: MutableList<TypeSyncResult>,
        failedTypes: MutableList<String> = mutableListOf(),
    ): Int {
        var totalRecords = 0

        val result = healthConnectRepository.getChanges(changesToken)

        if (result.tokenExpired) {
            preferencesRepository.updateChangesToken("")
            val startTime = Instant.now().minus(java.time.Duration.ofDays(DEFAULT_LOOKBACK_DAYS))
            return fullSync(startTime, Instant.now(), typeResults, failedTypes)
        }

        val totalTypes = result.upsertedRecords.size
        var completedCount = 0

        updateSyncState(SyncState.Syncing("", 0, totalTypes))

        for ((typeName, records) in result.upsertedRecords) {
            if (records.isNotEmpty()) {
                try {
                    log.i(TAG, "Delta: $typeName (${records.size} records)")
                    val json = healthConnectRepository.recordsToJson(records)
                    apiService.syncRecords(typeName, SyncRequest(json))
                    totalRecords += records.size
                    currentRecordCount = totalRecords
                    typeResults.add(TypeSyncResult(typeName, records.size))
                } catch (_: Exception) {
                    failedTypes.add(typeName)
                }
            }
            completedCount++
            updateSyncState(SyncState.Syncing(
                typeName, completedCount, totalTypes, totalRecords,
                completedTypes = typeResults.toList(),
            ))
        }

        if (result.nextToken.isNotBlank()) {
            preferencesRepository.updateChangesToken(result.nextToken)
        }

        return totalRecords
    }

    fun resetState() {
        _syncState.value = SyncState.Idle
    }

    private fun mergeJsonArrays(arrays: List<JsonElement>): JsonElement {
        if (arrays.size == 1) return arrays[0]
        val merged = JsonArray()
        for (element in arrays) {
            if (element.isJsonArray) {
                element.asJsonArray.forEach { merged.add(it) }
            } else {
                merged.add(element)
            }
        }
        return merged
    }
}
