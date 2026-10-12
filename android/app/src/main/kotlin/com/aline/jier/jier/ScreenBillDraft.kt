package com.aline.jier.jier

import org.json.JSONObject

/**
 * 页面识别出来的一条账单草稿。
 *
 * 它只是「待确认」的候选：无障碍服务读到页面文字、认出单笔之后先放这里，
 * 悬浮确认框让用户核对；确认后转成 LedgerCapture 进入正常记账管线。
 * 字段与 [LedgerCapture] 对齐，方便 Dart 侧直接构建 AutoCaptureRecord。
 */
data class ScreenBillDraft(
    val id: String,
    val packageName: String,
    /** wechat/alipay/…/bank/unknown，来自包名映射 */
    val source: String,
    /** 来源中文名，如「微信」「淘宝」，认不出时为「通知」 */
    val sourceLabel: String,
    val title: String,
    /** 店铺 / 商家 */
    val merchant: String,
    /** 商品 / 宝贝名称（买了什么） */
    val product: String,
    val counterpartyName: String,
    /** 页面原文（已截断），用于核对「看到了什么」 */
    val rawText: String,
    val scenario: String,
    val detailSummary: String,
    val amount: Double,
    val entryType: String,
    val channel: String,
    /** 识别那一刻的 ISO 时间（用于核对。保留期按 [capturedAtMillis] 算）。 */
    val capturedAt: String,
    /** 识别时刻（毫秒）。草稿保留期用这个，与【交易时间】[postedAtMillis] 可能不同。 */
    val capturedAtMillis: Long,
    /** 交易时间（毫秒）——从账单页读到的；读不到就退化为识别时刻。落账用它当记账时间。 */
    val postedAtMillis: Long,
    val confidence: Double,
    val defaultCategoryId: String,
    val profileId: Int,
    val mergeKey: String,
    /** 页面签名（数字挖空后的归一化文字），用于同页去重 */
    val signature: String,
    /** 用户是否已在悬浮确认框里点过「确认记账」 */
    val userConfirmed: Boolean,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("packageName", packageName)
        put("source", source)
        put("sourceLabel", sourceLabel)
        put("title", title)
        put("merchant", merchant)
        put("product", product)
        put("counterpartyName", counterpartyName)
        put("rawText", rawText)
        put("scenario", scenario)
        put("detailSummary", detailSummary)
        put("amount", amount)
        put("entryType", entryType)
        put("channel", channel)
        put("capturedAt", capturedAt)
        put("capturedAtMillis", capturedAtMillis)
        put("postedAtMillis", postedAtMillis)
        put("confidence", confidence)
        put("defaultCategoryId", defaultCategoryId)
        put("profileId", profileId)
        put("mergeKey", mergeKey)
        put("signature", signature)
        put("userConfirmed", userConfirmed)
    }

    /** 交给 Dart 的 Map，键与 AutoCaptureRecord 对齐（rawBody 用页面原文）。 */
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "packageName" to packageName,
        "source" to source,
        "sourceLabel" to sourceLabel,
        "title" to title,
        "merchant" to merchant,
        "product" to product,
        "counterpartyName" to counterpartyName,
        "rawBody" to rawText,
        "scenario" to scenario,
        "detailSummary" to detailSummary,
        "amount" to amount,
        "entryType" to entryType,
        "channel" to channel,
        "capturedAt" to capturedAt,
        "capturedAtMillis" to capturedAtMillis,
        "postedAtMillis" to postedAtMillis,
        "confidence" to confidence,
        "defaultCategoryId" to defaultCategoryId,
        "profileId" to profileId,
        "mergeKey" to mergeKey,
        "relatedSources" to emptyList<String>(),
        "signature" to signature,
        "userConfirmed" to userConfirmed,
    )

    /** 悬浮确认框里用户可能改了金额 / 店铺 / 商品，确认时回写并置 userConfirmed。 */
    fun confirmed(
        amount: Double? = null,
        merchant: String? = null,
        product: String? = null,
    ): ScreenBillDraft = copy(
        amount = amount ?: this.amount,
        merchant = merchant ?: this.merchant,
        product = product ?: this.product,
        userConfirmed = true,
    )

    companion object {
        fun fromJson(json: JSONObject): ScreenBillDraft = ScreenBillDraft(
            id = json.optString("id"),
            packageName = json.optString("packageName"),
            source = json.optString("source"),
            sourceLabel = json.optString("sourceLabel"),
            title = json.optString("title"),
            merchant = json.optString("merchant"),
            product = json.optString("product"),
            counterpartyName = json.optString("counterpartyName"),
            rawText = json.optString("rawText"),
            scenario = json.optString("scenario", "screenBill"),
            detailSummary = json.optString("detailSummary"),
            amount = json.optDouble("amount"),
            entryType = json.optString("entryType", "expense"),
            channel = json.optString("channel", "other"),
            capturedAt = json.optString("capturedAt"),
            capturedAtMillis = if (json.has("capturedAtMillis")) {
                json.optLong("capturedAtMillis")
            } else {
                json.optLong("postedAtMillis")
            },
            postedAtMillis = json.optLong("postedAtMillis"),
            confidence = json.optDouble("confidence"),
            defaultCategoryId = json.optString("defaultCategoryId", "daily"),
            profileId = json.optInt("profileId"),
            mergeKey = json.optString("mergeKey"),
            signature = json.optString("signature"),
            userConfirmed = json.optBoolean("userConfirmed", false),
        )
    }
}
