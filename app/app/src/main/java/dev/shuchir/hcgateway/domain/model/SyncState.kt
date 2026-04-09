package dev.shuchir.hcgateway.domain.model

sealed class SyncState {
    data object Idle : SyncState()
    data class Syncing(
        val currentType: String,
        val typesCompleted: Int,
        val totalTypes: Int,
        val recordsSynced: Int = 0,
        val completedTypes: List<TypeSyncResult> = emptyList(),
    ) : SyncState()
    data class Error(val message: String) : SyncState()
    data class Done(
        val recordCount: Int,
        val typeResults: List<TypeSyncResult> = emptyList(),
        val failedTypes: List<String> = emptyList(),
        val timestamp: Long = System.currentTimeMillis(),
    ) : SyncState()
    data class Cancelled(
        val recordCount: Int,
        val typeResults: List<TypeSyncResult> = emptyList(),
        val timestamp: Long = System.currentTimeMillis(),
    ) : SyncState()
}

data class TypeSyncResult(
    val typeName: String,
    val recordCount: Int,
)
