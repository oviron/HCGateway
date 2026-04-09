package dev.shuchir.hcgateway.data.repository

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.changes.Change
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.google.gson.Gson
import com.google.gson.JsonElement
import dev.shuchir.hcgateway.domain.model.RECORD_TYPES
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass

@Singleton
class HealthConnectRepository @Inject constructor(
    private val healthConnectClient: HealthConnectClient?,
    private val gson: Gson,
) {
    val isAvailable: Boolean get() = healthConnectClient != null

    val permissions: Set<String> by lazy {
        RECORD_TYPES.map { type ->
            HealthPermission.getReadPermission(type.recordClass)
        }.toSet() + setOf(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY)
    }

    // Required permissions for hasAllPermissions check (excludes optional permissions)
    private val requiredPermissions: Set<String> by lazy {
        RECORD_TYPES.map { type ->
            HealthPermission.getReadPermission(type.recordClass)
        }.toSet()
    }

    suspend fun hasAllPermissions(): Boolean {
        val client = healthConnectClient ?: return false
        val granted = client.permissionController.getGrantedPermissions()
        // Check that all granted-capable permissions are granted.
        // Permissions for experimental/unsupported record types (e.g. MindfulnessSession)
        // may not be recognized by Health Connect on this device and will never appear in
        // the granted set even with "Allow all". Count those as satisfied.
        val supported = granted + (requiredPermissions - granted).filter { perm ->
            // A permission is unsupported if HC doesn't list it at all.
            // Heuristic: if the READ permission for this type is not in granted,
            // it's likely unsupported on this device (e.g. MindfulnessSession).
            val base = perm.substringAfterLast(".")
                .removePrefix("READ_")
            val hasReadPerm = "android.permission.health.READ_$base" in granted
            !hasReadPerm
        }
        return requiredPermissions.all { it in supported }
    }

    suspend fun readRecords(
        recordClass: KClass<out Record>,
        startTime: Instant,
        endTime: Instant,
    ): List<Record> {
        val allRecords = mutableListOf<Record>()
        readRecordsPaged(recordClass, startTime, endTime) { page ->
            allRecords.addAll(page)
        }
        return allRecords
    }

    /**
     * Reads records page by page (1000 records per page), calling [onPage] for each page.
     * This avoids loading all records into memory at once.
     * Returns the total number of records read.
     */
    suspend fun readRecordsPaged(
        recordClass: KClass<out Record>,
        startTime: Instant,
        endTime: Instant,
        onPage: suspend (List<Record>) -> Unit,
    ): Int {
        val client = healthConnectClient ?: return 0
        var pageToken: String? = null
        var total = 0

        do {
            val request = ReadRecordsRequest(
                recordType = recordClass,
                timeRangeFilter = TimeRangeFilter.between(startTime, endTime),
                pageToken = pageToken,
            )
            val response = client.readRecords(request)
            if (response.records.isNotEmpty()) {
                onPage(response.records)
                total += response.records.size
            }
            pageToken = response.pageToken
        } while (pageToken != null)

        return total
    }

    fun recordsToJson(records: List<Record>): JsonElement {
        return RecordSerializer.serializeRecords(records)
    }

    suspend fun getChangesToken(): String {
        val client = healthConnectClient ?: throw IllegalStateException("Health Connect not available")
        // Only request changes for record types that Health Connect supports on this device.
        // Unsupported types (e.g. MindfulnessSession) cause SecurityException.
        val granted = client.permissionController.getGrantedPermissions()
        val supportedTypes = RECORD_TYPES.filter { type ->
            val perm = HealthPermission.getReadPermission(type.recordClass)
            perm in granted
        }.map { it.recordClass }.toSet()
        val request = ChangesTokenRequest(recordTypes = supportedTypes)
        return client.getChangesToken(request)
    }

    data class ChangeResult(
        val upsertedRecords: Map<String, List<Record>>,
        val nextToken: String,
        val hasMore: Boolean,
        val tokenExpired: Boolean,
    )

    suspend fun getChanges(token: String): ChangeResult {
        val client = healthConnectClient ?: throw IllegalStateException("Health Connect not available")
        val upserted = mutableMapOf<String, MutableList<Record>>()

        var currentToken = token
        var hasMore = true

        try {
            while (hasMore) {
                val response = client.getChanges(currentToken)
                for (change in response.changes) {
                    when (change) {
                        is UpsertionChange -> {
                            val record = change.record
                            val typeName = recordClassName(record::class)
                            upserted.getOrPut(typeName) { mutableListOf() }.add(record)
                        }
                        is DeletionChange -> {
                            // DeletionChange doesn't carry type info — skip for now
                        }
                    }
                }
                hasMore = response.hasMore
                currentToken = response.nextChangesToken
            }
        } catch (e: Exception) {
            if (e.message?.contains("token", ignoreCase = true) == true) {
                return ChangeResult(emptyMap(), "", false, tokenExpired = true)
            }
            // Some records in Health Connect may have invalid data (e.g. startTime >= endTime).
            // Return what we have so far rather than failing the entire sync.
            if (e is IllegalArgumentException) {
                android.util.Log.w("HealthConnect", "Skipping corrupt record in changes: ${e.message}")
                return ChangeResult(upserted, currentToken, false, tokenExpired = false)
            }
            throw e
        }

        return ChangeResult(upserted, currentToken, false, tokenExpired = false)
    }

    private fun recordClassName(kClass: KClass<out Record>): String {
        return RECORD_TYPES.find { it.recordClass == kClass }?.name ?: kClass.simpleName ?: "Unknown"
    }
}
