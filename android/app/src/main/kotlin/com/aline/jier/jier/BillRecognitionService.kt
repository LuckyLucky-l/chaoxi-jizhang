package com.aline.jier.jier

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 页面识别：用户打开账单详情页时，读页面文字、认出一笔，**在当前页面上弹出确认框**。
 *
 * 用无障碍悬浮层（TYPE_ACCESSIBILITY_OVERLAY，不需要额外权限）。
 * 只做三件事：取文字、识别、弹框——识别结果进待确认箱，用户在框里确认后才去记账。
 * 不调用模型、不写账本。
 */
class BillRecognitionService : AccessibilityService() {
    private var lastEventAt: Long = 0L
    private var lastSignature: String = ""
    private var lastSignatureAt: Long = 0L

    private var overlayView: View? = null
    private var overlayDraft: ScreenBillDraft? = null

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val current = event ?: return
        if (current.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            current.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            return
        }
        val packageName = current.packageName?.toString().orEmpty()
        if (packageName.isBlank() || packageName == this.packageName) return

        val now = System.currentTimeMillis()
        if (now - lastEventAt < MIN_INTERVAL_MS) return
        lastEventAt = now

        val root = rootInActiveWindow ?: return
        val texts = collectTexts(root)
        if (texts.isEmpty()) return

        val draft = runCatching {
            BillPageParser.parse(packageName, texts, now)
        }.getOrNull() ?: return

        if (draft.signature == lastSignature && now - lastSignatureAt < DEDUP_WINDOW_MS) {
            return
        }
        lastSignature = draft.signature
        lastSignatureAt = now

        runCatching {
            ScreenCaptureStore.enqueue(applicationContext, draft)
            if (draft.confidence >= NOTIFY_CONFIDENCE_THRESHOLD) {
                showOverlay(draft)
            }
        }.onFailure { error ->
            Log.w(TAG, "screen bill store failed: ${error.message}")
        }
        Log.d(
            TAG,
            "screen bill captured source=${draft.source} amount=${draft.amount} conf=${draft.confidence}",
        )
    }

    override fun onInterrupt() {
        // 服务被系统中断：草稿已在待确认箱里，下次打开应用还能核对。
    }

    override fun onDestroy() {
        dismissOverlay()
        super.onDestroy()
    }

    // ---------------- 悬浮确认框 ----------------

    private fun showOverlay(draft: ScreenBillDraft) {
        val windowManager = getSystemService(WINDOW_SERVICE) as? WindowManager ?: return
        dismissOverlay()

        val view = runCatching {
            LayoutInflater.from(this).inflate(R.layout.screen_bill_overlay, null)
        }.getOrNull() ?: return

        val typeLabel = when (draft.entryType) {
            "income" -> "收入"
            "transfer" -> "转账"
            else -> "支出"
        }
        view.findViewById<TextView>(R.id.overlay_source).text =
            "来源：${draft.sourceLabel} · $typeLabel"
        view.findViewById<EditText>(R.id.overlay_amount).setText(
            String.format(Locale.US, "%.2f", draft.amount),
        )
        view.findViewById<EditText>(R.id.overlay_merchant).setText(
            if (draft.merchant == "未识别商户") "" else draft.merchant,
        )
        view.findViewById<EditText>(R.id.overlay_product).setText(draft.product)
        view.findViewById<TextView>(R.id.overlay_time).text =
            "交易时间：${timeFormat.format(Date(draft.postedAtMillis))}"

        view.findViewById<Button>(R.id.overlay_ignore).setOnClickListener {
            ScreenCaptureStore.remove(applicationContext, draft.id)
            dismissOverlay()
        }
        view.findViewById<Button>(R.id.overlay_confirm).setOnClickListener {
            confirmOverlay(view, draft)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }

        val added = runCatching {
            windowManager.addView(view, params)
            true
        }.getOrElse { error ->
            Log.w(TAG, "overlay add failed: ${error.message}")
            false
        }
        if (added) {
            overlayView = view
            overlayDraft = draft
        }
    }

    private fun confirmOverlay(view: View, draft: ScreenBillDraft) {
        val amountText = view.findViewById<EditText>(R.id.overlay_amount).text?.toString()
            ?.trim()?.replace(",", "")
        val amount = amountText?.toDoubleOrNull()?.takeIf { it > 0 } ?: draft.amount
        val merchant = view.findViewById<EditText>(R.id.overlay_merchant).text?.toString()
            ?.trim().orEmpty()
        val product = view.findViewById<EditText>(R.id.overlay_product).text?.toString()
            ?.trim().orEmpty()

        val confirmed = draft.confirmed(
            amount = amount,
            merchant = merchant.ifBlank { draft.merchant },
            product = product,
        )
        ScreenCaptureStore.enqueue(applicationContext, confirmed)
        dismissOverlay()
        // 打开应用：Flutter 侧看到「已确认」的草稿会直接落账（不再二次弹框）。
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                ),
            )
        }
    }

    private fun dismissOverlay() {
        val view = overlayView ?: return
        overlayView = null
        overlayDraft = null
        runCatching {
            (getSystemService(WINDOW_SERVICE) as? WindowManager)?.removeView(view)
        }
    }

    /** 广度优先收集本页可见文字（text + contentDescription），顺序稳定。 */
    private fun collectTexts(root: AccessibilityNodeInfo): List<String> {
        val collected = LinkedHashSet<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited += 1
            node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(collected::add)
            node.contentDescription?.toString()?.trim()
                ?.takeIf { it.isNotBlank() }?.let(collected::add)
            val childCount = node.childCount
            for (index in 0 until childCount) {
                node.getChild(index)?.let(queue::add)
            }
        }
        return collected.toList()
    }

    companion object {
        private const val TAG = "JierScreenBill"

        /** 单页最多读取的节点数，避免超大页面拖慢/耗电。 */
        private const val MAX_NODES = 800

        /** 两次识别之间至少间隔的时间，抑制页面抖动导致的高频事件。 */
        private const val MIN_INTERVAL_MS = 700L

        /** 同一页面签名在此时长内不重复处理。 */
        private const val DEDUP_WINDOW_MS = 60_000L

        /** 置信度达到此值才弹悬浮确认框，否则只留在待确认箱里。 */
        const val NOTIFY_CONFIDENCE_THRESHOLD = 0.5
    }
}
