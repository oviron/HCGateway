package dev.shuchir.hcgateway.data.remote

import dev.shuchir.hcgateway.data.repository.LogRequest
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.POST
import retrofit2.http.Path

interface ApiService {

    @POST("api/v2/login")
    suspend fun login(@Body request: LoginRequest): Response<LoginResponse>

    @POST("api/v2/refresh")
    suspend fun refresh(@Body request: RefreshRequest): Response<RefreshResponse>

    @POST("api/v2/sync/{recordType}")
    suspend fun syncRecords(
        @Path("recordType") recordType: String,
        @Body request: SyncRequest,
    ): Response<Unit>

    @HTTP(method = "DELETE", path = "api/v2/sync/{recordType}", hasBody = true)
    suspend fun deleteRecords(
        @Path("recordType") recordType: String,
        @Body request: DeleteRequest,
    ): Response<Unit>

    @GET("api/v2/counts")
    suspend fun getCounts(): Response<Map<String, Int>>

    @POST("api/v2/logs")
    suspend fun sendLogs(@Body request: LogRequest): Response<Unit>
}
