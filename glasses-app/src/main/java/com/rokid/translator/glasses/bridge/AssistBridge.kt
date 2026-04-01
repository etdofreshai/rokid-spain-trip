package com.rokid.translator.glasses.bridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import android.util.Log
import android.os.IInterface
import org.json.JSONObject

class AssistBridge(private val context: Context) {

    companion object {
        private const val TAG = "AssistBridge"
        private const val ASSIST_PKG = "com.rokid.os.sprite.assistserver"
        private const val ASSIST_SVC = "com.rokid.os.sprite.assist.MasterAssistService"
        private const val DESC_SERVER = "com.rokid.os.sprite.assist.server.IAssistServer"
        private const val DESC_CLIENT = "com.rokid.os.sprite.assist.client.IAssistClient"
        private const val TX_REGISTER = 1
        private const val TX_UNREGISTER = 2
        private const val TX_CONTROL = 3
        private const val TX_REGISTER_RESULT = 1
        private const val TX_ON_MSG = 2
        private const val TX_ON_DATA = 3
        private const val TYPE_HANDLE_MSG_RESULT = "cmd_handle_msg_result"
        private const val TYPE_TRANSLATE_RESULT = "cmd_bluetooth_gatt_translate_result"
        private const val TYPE_NOTIFY_SCENE_STATUS = "cmd_notify_scene_status"
        private const val CMD_PHONE_GATT_SEND_DATA = "cmd_phone_gatt_send_data"
        private const val CMD_SCENE_STATUS_CHANGE = "CMD_SCENE_STATUS_CHANGE"
    }

    interface Listener {
        fun onConnected()
        fun onDisconnected()
        fun onTranslationMessage(cmd: String, subCmd: String, data: String)
        fun onSceneStatus(translateRunning: Boolean)
    }

    private var binder: IBinder? = null
    private var listener: Listener? = null
    private var connected = false
    private val clientBinder = ClientBinder()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "Connected: $name")
            binder = service
            connected = true
            doRegister()
            mainHandler.post { listener?.onConnected() }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "Disconnected: $name")
            binder = null
            connected = false
            mainHandler.post { listener?.onDisconnected() }
            mainHandler.postDelayed({ bind() }, 5000)
        }
    }

    fun setListener(l: Listener) { listener = l }

    fun bind() {
        try {
            val i = Intent().apply { component = ComponentName(ASSIST_PKG, ASSIST_SVC) }
            context.bindService(i, conn, Context.BIND_AUTO_CREATE)
            Log.d(TAG, "Binding...")
        } catch (e: Exception) { Log.e(TAG, "Bind failed", e) }
    }

    fun unbind() {
        try { doUnregister(); context.unbindService(conn) } catch (_: Exception) {}
        binder = null; connected = false
    }

    fun startTranslation() {
        sendGatt("Trans", "Trans_Start", "")
        sendScene("translate", true)
    }

    fun stopTranslation() {
        sendGatt("Trans", "Trans_Stop", "")
        sendScene("translate", false)
    }

    fun isConnected() = connected && binder != null

    private fun transact(code: Int, block: (Parcel) -> Unit) {
        val b = binder ?: return
        val d = Parcel.obtain(); val r = Parcel.obtain()
        try {
            d.writeInterfaceToken(DESC_SERVER)
            d.writeString(context.packageName)
            block(d)
            b.transact(code, d, r, 0)
            r.readException()
        } catch (e: Exception) { Log.e(TAG, "Transact $code failed", e) }
        finally { d.recycle(); r.recycle() }
    }

    private fun doRegister() = transact(TX_REGISTER) { it.writeStrongBinder(clientBinder) }
    private fun doUnregister() = transact(TX_UNREGISTER) {}

    private fun sendControl(json: String) = transact(TX_CONTROL) { it.writeString(json) }

    private fun sendGatt(cmd: String, key: String, value: String) {
        try {
            val inner = JSONObject().put("cmd", cmd).put("key", key).put("data", value)
            sendControl(JSONObject().put("type", CMD_PHONE_GATT_SEND_DATA).put("data", inner).toString())
        } catch (e: Exception) { Log.e(TAG, "GATT msg failed", e) }
    }

    private fun sendScene(scene: String, active: Boolean) {
        try {
            val inner = JSONObject().put("sceneKey", scene).put("status", active)
            sendControl(JSONObject().put("type", CMD_SCENE_STATUS_CHANGE).put("data", inner).toString())
        } catch (e: Exception) { Log.e(TAG, "Scene msg failed", e) }
    }

    private inner class ClientBinder : Binder(), IInterface {
        init { attachInterface(this, DESC_CLIENT) }

        override fun asBinder(): IBinder = this

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code in 1..16777215) data.enforceInterface(DESC_CLIENT)
            when (code) {
                INTERFACE_TRANSACTION -> { reply?.writeString(DESC_CLIENT); return true }
                TX_REGISTER_RESULT -> {
                    if (data.readInt() != 0) {
                        // RegisterResult is an empty parcelable in the decompiled app.
                    }
                    reply?.writeNoException()
                    return true
                }
                TX_ON_MSG -> {
                    try {
                        if (data.readInt() == 0) {
                            reply?.writeNoException()
                            reply?.writeInt(0)
                            return true
                        }

                        val messageId = data.readLong()
                        val packageName = data.readString()
                        val infoType = data.readString().orEmpty()
                        val time = data.readLong()
                        val msg = data.readString()

                        Log.d(TAG, "Assist msg: id=$messageId pkg=$packageName type=$infoType time=$time")
                        if (infoType == TYPE_HANDLE_MSG_RESULT && msg != null) parseMsg(msg)

                        reply?.writeNoException()
                        reply?.writeInt(1)
                    } catch (e: Exception) {
                        Log.e(TAG, "Parse error", e)
                        reply?.writeNoException()
                        reply?.writeInt(0)
                    }
                    return true
                }
                TX_ON_DATA -> {
                    val key = data.readString().orEmpty()
                    val param = data.readString().orEmpty()
                    val bytes = data.createByteArray() ?: ByteArray(0)
                    Log.d(TAG, "Assist data: key=$key param=$param bytes=${bytes.size}")
                    reply?.writeNoException()
                    reply?.writeByteArray(bytes)
                    return true
                }
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    private fun parseMsg(json: String) {
        try {
            val root = JSONObject(json)
            val type = root.optString("type", "")
            val data = root.optJSONObject("data") ?: return

            when (type) {
                TYPE_TRANSLATE_RESULT -> {
                    val cmd = data.optString("command").ifEmpty { data.optString("cmd", "Trans") }
                    val sub = data.optString("caps0", "")
                    val payload = when {
                        data.has("caps2") -> data.optString("caps2", "")
                        data.has("caps1") -> data.optString("caps1", "")
                        else -> data.toString()
                    }
                    Log.d(TAG, "Translation: cmd=$cmd sub=$sub payload=$payload")
                    mainHandler.post { listener?.onTranslationMessage(cmd, sub, payload) }
                }

                TYPE_NOTIFY_SCENE_STATUS -> {
                    val translateRunning = data.optBoolean("translateRunning", false)
                    Log.d(TAG, "Scene status: translateRunning=$translateRunning")
                    mainHandler.post { listener?.onSceneStatus(translateRunning) }
                }

                else -> {
                    Log.d(TAG, "Ignoring assist payload type=$type json=$json")
                }
            }
        } catch (e: Exception) { Log.e(TAG, "Parse msg error", e) }
    }
}
