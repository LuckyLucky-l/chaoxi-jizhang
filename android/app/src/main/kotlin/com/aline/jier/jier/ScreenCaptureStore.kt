package com.aline.jier.jier

import android.content.Context
import org.json.JSONArray

/**
 * 页面识别的「待确认箱」。
 *
 * 无障碍服务读到一页账单后把草稿放进来（[enqueue]），用户确认或忽略后移除（[remove]）。
 * 只存在应用私有目录；上限 30 条，超过 7 天的自动清掉——页面识别是补漏，不长期囤积。
 */
object ScreenCaptureStore {
    private const val PREFS_NAME = "jier_screen_bills"
    private const val KEY_QUEUE = "pending_bills"
    private const val KEY_DROPPED_TOTAL = "dropped_total"

    /** 待确认箱上限。超出时丢最旧的，并记下丢了多少条。 */
    private const val MAX_RECORDS = 30

    /** 草稿保留时长：7 天。 */
    private const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000

    /**
     * 入队。同一页面签名已存在时用新的覆盖（页面可能更新），
     * 并按上限与保留时长做清理。
     */
    fun enqueue(context: Context, draft: ScreenBillDraft) {
        synchronized(this) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val queue = readQueue(prefs)
                .filterNot { it.id == draft.id }
                .filterNot { draft.signature.isNotBlank() && it.signature == draft.signature }
                .toMutableList()
            queue += draft
            pruneExpired(queue)
            var dropped = prefs.getInt(KEY_DROPPED_TOTAL, 0)
            while (queue.size > MAX_RECORDS) {
                queue.removeAt(0)
                dropped += 1
            }
            prefs.edit()
                .putString(KEY_QUEUE, encode(queue))
                .putInt(KEY_DROPPED_TOTAL, dropped)
                .apply()
        }
    }

    /** 读取未处理的草稿（已清理过期项），按入队先后返回。 */
    fun peek(context: Context): List<ScreenBillDraft> {
        synchronized(this) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val queue = readQueue(prefs)
            val before = queue.size
            pruneExpired(queue)
            if (queue.size != before) {
                prefs.edit().putString(KEY_QUEUE, encode(queue)).apply()
            }
            return queue
        }
    }

    fun pendingCount(context: Context): Int = peek(context).size

    fun droppedTotal(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_DROPPED_TOTAL, 0)

    /** 用户确认或忽略后移除该草稿。 */
    fun remove(context: Context, id: String) {
        if (id.isBlank()) return
        synchronized(this) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val queue = readQueue(prefs)
            val remaining = queue.filterNot { it.id == id }
            if (remaining.size != queue.size) {
                prefs.edit().putString(KEY_QUEUE, encode(remaining)).apply()
            }
        }
    }

    private fun pruneExpired(queue: MutableList<ScreenBillDraft>) {
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        // 按【识别时刻】清理，而不是交易时间：几天前的账单今天才打开，草稿也不该被立刻清掉。
        queue.removeAll { it.capturedAtMillis in 1 until cutoff }
    }

    private fun readQueue(prefs: android.content.SharedPreferences): MutableList<ScreenBillDraft> {
        val raw = prefs.getString(KEY_QUEUE, "[]") ?: "[]"
        val array = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        val list = mutableListOf<ScreenBillDraft>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            list += ScreenBillDraft.fromJson(item)
        }
        return list
    }

    private fun encode(records: List<ScreenBillDraft>): String {
        val array = JSONArray()
        records.forEach { array.put(it.toJson()) }
        return array.toString()
    }
}
