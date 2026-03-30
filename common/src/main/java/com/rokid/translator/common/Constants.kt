package com.rokid.translator.common

import java.util.UUID

object Constants {
    val BT_SERVICE_UUID: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890")
    
    const val HEARTBEAT_INTERVAL_MS = 5000L
    const val CONNECTION_TIMEOUT_MS = 10000L
    const val RECONNECT_DELAY_MS = 3000L
    const val MAX_RECONNECT_ATTEMPTS = 5
    
    const val AUDIO_SAMPLE_RATE = 16000
    const val AUDIO_CHANNEL_CONFIG = 16
    const val AUDIO_ENCODING = 2
    const val AUDIO_BUFFER_SIZE = 4096
    
    const val NOTIFICATION_CHANNEL_ID = "rokid_translator_service"
    const val NOTIFICATION_ID = 1001
}
