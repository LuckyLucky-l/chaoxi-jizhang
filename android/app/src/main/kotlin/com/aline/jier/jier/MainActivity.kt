package com.aline.jier.jier

import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.embedding.android.RenderMode
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterFragmentActivity() {
    private val vaultCipher by lazy { VaultCipher() }
    private val localModelDownloads by lazy { LocalModelDownloadBridge(applicationContext) }
    private val privacyPrefs: SharedPreferences by lazy {
        getSharedPreferences(PRIVACY_PREFS, MODE_PRIVATE)
    }

    /** 页面识别通知点进来时带的账单 id，交给 Dart 侧消费一次。 */
    private var pendingBillId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        applyScreenCaptureAllowed(loadScreenCaptureAllowed())
        consumeBillIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeBillIntent(intent)
    }

    override fun getRenderMode(): RenderMode {
        return RenderMode.texture
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, VAULT_CRYPTO_CHANNEL)
            .setMethodCallHandler(::handleCrypto)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, AUTO_CAPTURE_CHANNEL)
            .setMethodCallHandler(::handleAutoCapture)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, WINDOW_PRIVACY_CHANNEL)
            .setMethodCallHandler(::handleWindowPrivacy)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, MODEL_DOWNLOAD_CHANNEL)
            .setMethodCallHandler(::handleModelDownload)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, BILL_RECOGNITION_CHANNEL)
            .setMethodCallHandler(::handleBillRecognition)
    }

    private fun handleModelDownload(call: MethodCall, result: MethodChannel.Result) {
        // 这个自检不需要模型编号，必须放在下面取 modelId 之前。
        // （之前放在 when 里面，取不到 modelId 会先抛错，导致所有手机都被误判成“不支持”。）
        if (call.method == "canRunLocalAi") {
            val available = runCatching {
                System.loadLibrary("llama_jni")
                true
            }.getOrElse { error ->
                android.util.Log.w(
                    "JierAutoCapture",
                    "canRunLocalAi=false: ${error.message}",
                )
                false
            }
            android.util.Log.d("JierAutoCapture", "canRunLocalAi=$available")
            result.success(available)
            return
        }
        runCatching {
            val modelId = call.argument<String>("modelId") ?: error("缺少模型编号")
            when (call.method) {
                "start" -> localModelDownloads.start(
                    modelId,
                    call.argument<String>("url") ?: error("缺少下载地址"),
                    call.argument<String>("alternateUrl"),
                    call.argument<String>("title") ?: "千问模型",
                )
                "query" -> localModelDownloads.query(modelId)
                "cancel" -> {
                    localModelDownloads.cancel(modelId)
                    null
                }
                else -> throw IllegalArgumentException("未支持的方法：${call.method}")
            }
        }.onSuccess(result::success).onFailure {
            result.error("model_download_failed", it.message ?: "模型下载操作失败", null)
        }
    }

    private fun handleBillRecognition(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "isServiceEnabled" -> result.success(isBillRecognitionEnabled())
            "openAccessibilitySettings" -> {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                result.success(null)
            }
            "fetchPendingBills" ->
                result.success(ScreenCaptureStore.peek(applicationContext).map { it.toMap() })
            "confirmBill", "dismissBill" -> {
                val id = call.argument<String>("id").orEmpty()
                ScreenCaptureStore.remove(applicationContext, id)
                BillConfirmNotifier.cancel(applicationContext, id)
                result.success(null)
            }
            // 通知点进来时带的账单 id，只消费一次。
            "takeLaunchBillId" -> {
                val id = pendingBillId
                pendingBillId = null
                result.success(id)
            }
            "screenBillStats" -> {
                result.success(
                    mapOf(
                        "pendingCount" to ScreenCaptureStore.pendingCount(applicationContext),
                        "droppedTotal" to ScreenCaptureStore.droppedTotal(applicationContext),
                    ),
                )
            }
            else -> result.notImplemented()
        }
    }

    private fun isBillRecognitionEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            "enabled_accessibility_services",
        ) ?: return false
        val expected = ComponentName(this, BillRecognitionService::class.java)
        return enabled.split(':').any { entry ->
            ComponentName.unflattenFromString(entry.trim()) == expected
        }
    }

    private fun consumeBillIntent(intent: Intent?) {
        val id = intent?.getStringExtra(BillConfirmNotifier.EXTRA_BILL_ID)
        if (!id.isNullOrBlank()) {
            pendingBillId = id
        }
    }

    private fun handleCrypto(call: MethodCall, result: MethodChannel.Result) {
        val payload = call.argument<String>("payload")
        val passphrase = call.argument<String>("passphrase")
        if (payload.isNullOrBlank() || passphrase.isNullOrBlank()) {
            result.error("invalid_args", "payload 或 passphrase 为空。", null)
            return
        }

        runCatching {
            when (call.method) {
                "encryptLedger" -> vaultCipher.encrypt(payload, passphrase)
                "decryptLedger" -> vaultCipher.decrypt(payload, passphrase)
                else -> throw IllegalArgumentException("未支持的方法：${call.method}")
            }
        }.onSuccess(result::success).onFailure {
            result.error("crypto_failed", it.message ?: "加密通道失败。", null)
        }
    }

    private fun handleAutoCapture(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "isNotificationAccessEnabled" -> result.success(isNotificationListenerEnabled())
            "openNotificationAccessSettings" -> {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                result.success(null)
            }
            "fetchPendingAutoRecords" -> {
                result.success(AutoCaptureStore.peek(applicationContext).map { it.toMap() })
            }
            "acknowledgeAutoRecords" -> {
                val records = call.argument<List<Map<String, Any?>>>("records").orEmpty()
                AutoCaptureStore.acknowledge(applicationContext, records)
                result.success(null)
            }
            // 通知原文收件箱：核对“收到的原文 → 记成了什么”
            "fetchRawNotifications" -> {
                val limit = call.argument<Int>("limit") ?: 60
                result.success(
                    RawNotificationStore.peek(applicationContext, limit).map { it.toMap() }
                )
            }
            "clearRawNotifications" -> {
                RawNotificationStore.clear(applicationContext)
                result.success(null)
            }
            // #15 后台常驻：队列还有货时把进程留住，让 Flutter 侧继续消化
            "startCaptureDrain" -> {
                val pending = call.argument<Int>("pending") ?: 0
                CaptureDrainService.start(applicationContext, pending)
                result.success(true)
            }
            "stopCaptureDrain" -> {
                CaptureDrainService.stop(applicationContext)
                result.success(true)
            }
            "autoCaptureStats" -> {
                val stats = RawNotificationStore.stats(applicationContext).toMutableMap()
                stats["pendingCount"] = AutoCaptureStore.pendingCount(applicationContext)
                stats["pendingDroppedTotal"] =
                    AutoCaptureStore.droppedTotal(applicationContext)
                result.success(stats)
            }
            else -> result.notImplemented()
        }
    }

    private fun handleWindowPrivacy(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "setScreenCaptureAllowed" -> {
                val allowed = call.argument<Boolean>("allowed") ?: false
                val persist = call.argument<Boolean>("persist") ?: true
                runOnUiThread {
                    if (persist) {
                        persistScreenCaptureAllowed(allowed)
                    }
                    applyScreenCaptureAllowed(allowed)
                }
                result.success(null)
            }
            "isScreenCaptureAllowed" -> result.success(loadScreenCaptureAllowed())
            else -> result.notImplemented()
        }
    }

    private fun loadScreenCaptureAllowed(): Boolean {
        return privacyPrefs.getBoolean(KEY_ALLOW_SCREENSHOTS, true)
    }

    private fun persistScreenCaptureAllowed(allowed: Boolean) {
        privacyPrefs.edit().putBoolean(KEY_ALLOW_SCREENSHOTS, allowed).apply()
    }

    private fun applyScreenCaptureAllowed(allowed: Boolean) {
        if (allowed) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val enabledListeners = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        val expected = ComponentName(
            this,
            LedgerNotificationListenerService::class.java
        ).flattenToString()
        return enabledListeners.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private companion object {
        const val VAULT_CRYPTO_CHANNEL = "com.aline.jier/vault_crypto"
        const val AUTO_CAPTURE_CHANNEL = "com.aline.jier/auto_capture"
        const val WINDOW_PRIVACY_CHANNEL = "com.aline.jier/window_privacy"
        const val MODEL_DOWNLOAD_CHANNEL = "com.aline.jier/model_download"
        const val BILL_RECOGNITION_CHANNEL = "com.aline.jier/bill_recognition"
        const val PRIVACY_PREFS = "jier_window_privacy"
        const val KEY_ALLOW_SCREENSHOTS = "allow_screenshots"
    }
}
