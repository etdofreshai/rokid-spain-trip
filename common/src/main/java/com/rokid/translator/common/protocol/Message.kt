package com.rokid.translator.common.protocol

import android.util.Base64
import org.json.JSONObject
import java.util.UUID

data class Message(
    val id: String = UUID.randomUUID().toString(),
    val type: MessageType,
    val timestamp: Long = System.currentTimeMillis(),
    val payload: String? = null,
    val binaryData: ByteArray? = null
) {
    companion object {
        fun fromJson(json: String): Message? {
            return try {
                val jsonObj = JSONObject(json)
                val typeCode = jsonObj.getInt("type")
                val type = MessageType.fromCode(typeCode) ?: return null
                val id = jsonObj.optString("id", UUID.randomUUID().toString())
                val timestamp = jsonObj.optLong("timestamp", System.currentTimeMillis())
                val payload = if (jsonObj.has("payload")) jsonObj.getString("payload") else null
                val binaryData = if (jsonObj.has("binaryData")) {
                    try { Base64.decode(jsonObj.getString("binaryData"), Base64.NO_WRAP) }
                    catch (e: Exception) { null }
                } else null
                Message(id = id, type = type, timestamp = timestamp, payload = payload, binaryData = binaryData)
            } catch (e: Exception) { null }
        }
        
        fun voiceStart() = Message(type = MessageType.VOICE_START)
        fun voiceEnd() = Message(type = MessageType.VOICE_END)
        fun aiProcessing(status: String) = Message(type = MessageType.AI_PROCESSING, payload = status)
        fun aiResponse(text: String) = Message(type = MessageType.AI_RESPONSE_TEXT, payload = text)
        fun aiError(error: String) = Message(type = MessageType.AI_ERROR, payload = error)
        fun displayText(text: String) = Message(type = MessageType.DISPLAY_TEXT, payload = text)
        fun displayClear() = Message(type = MessageType.DISPLAY_CLEAR)
        fun userTranscript(text: String) = Message(type = MessageType.USER_TRANSCRIPT, payload = text)
    }
    
    fun toJson(): String {
        val jsonObj = JSONObject()
        jsonObj.put("id", id)
        jsonObj.put("type", type.code)
        jsonObj.put("timestamp", timestamp)
        if (payload != null) jsonObj.put("payload", payload)
        if (binaryData != null) jsonObj.put("binaryData", Base64.encodeToString(binaryData, Base64.NO_WRAP))
        return jsonObj.toString()
    }
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Message) return false
        return id == other.id && type == other.type
    }
    
    override fun hashCode(): Int = id.hashCode()
}
