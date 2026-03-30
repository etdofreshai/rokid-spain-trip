package com.rokid.translator.common.protocol

enum class MessageType(val code: Int) {
    // Connection management
    HANDSHAKE(0x00),
    HANDSHAKE_ACK(0x01),
    HEARTBEAT(0x02),
    HEARTBEAT_ACK(0x03),
    DISCONNECT(0x0F),
    
    // Voice related
    VOICE_START(0x10),
    VOICE_DATA(0x11),
    VOICE_END(0x12),
    VOICE_CANCEL(0x13),
    
    // Translation results (reusing AI codes)
    AI_PROCESSING(0x20),
    AI_RESPONSE_TEXT(0x21),    // Used for translation result
    USER_TRANSCRIPT(0x23),
    AI_ERROR(0x2F),
    
    // Display control
    DISPLAY_TEXT(0x30),
    DISPLAY_CLEAR(0x31),
    DISPLAY_STATUS(0x32),
    
    // System
    SYSTEM_STATUS(0xF0),
    SYSTEM_CONFIG(0xF1),
    SYSTEM_ERROR(0xFF);
    
    companion object {
        fun fromCode(code: Int): MessageType? = entries.find { it.code == code }
    }
}
