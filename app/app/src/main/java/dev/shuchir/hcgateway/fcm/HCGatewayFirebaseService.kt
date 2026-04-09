package dev.shuchir.hcgateway.fcm

import com.google.firebase.messaging.FirebaseMessagingService
import dagger.hilt.android.AndroidEntryPoint
import dev.shuchir.hcgateway.data.local.PreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class HCGatewayFirebaseService : FirebaseMessagingService() {

    @Inject lateinit var preferencesRepository: PreferencesRepository

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onNewToken(token: String) {
        scope.launch {
            preferencesRepository.updateFcmToken(token)
        }
    }
}
