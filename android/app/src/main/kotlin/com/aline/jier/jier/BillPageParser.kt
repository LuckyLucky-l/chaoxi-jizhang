package com.aline.jier.jier

import java.security.MessageDigest
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 从「页面文字」里认出一笔账单。
 *
 * 输入是无障碍服务收集到的一页可见文字（`node.text` / `contentDescription`），
 * 输出一条待确认的 [ScreenBillDraft]；认不出就返回 null。
 *
 * 纯函数、不碰 Android 上下文，方便用 JVM 单测覆盖（见 BillPageParserTest）。
 * 说明：WebView / 自绘页面暴露的节点很少或是乱码，这类页面会识别失败——v1 接受此限制。
 */
object BillPageParser {
    private const val AMOUNT_PATTERN =
        """([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)"""

    private const val DAY_MS = 24 * 60 * 60 * 1000L

    private const val MARKED_REGEX_COUNT = 2

    /** 金额正则：前两条带货币标记（更可信），第三条只看「金额」标签（较不可信）。 */
    private val amountRegexes = listOf(
        Regex("""(?:¥|￥|CNY|RMB)\s*$AMOUNT_PATTERN""", RegexOption.IGNORE_CASE),
        Regex("""$AMOUNT_PATTERN\s*(?:元|圓|块|塊)"""),
        Regex("""(?:金额|金額|实付|實付|付款金额|付款金額|amount)[：:\s]*$AMOUNT_PATTERN""", RegexOption.IGNORE_CASE),
    )

    private val billKeywords = listOf(
        "交易", "账单", "訂單", "订单", "收款", "付款", "支付", "消费", "消費", "扣款",
        "金额", "金額", "实付", "實付", "商户", "商戶", "商家", "收款方", "付款方",
        "转账", "轉帳", "转賬", "轉賬", "退款", "已支付", "付款成功", "交易成功", "支付成功",
    )

    private val expenseKeywords = listOf(
        "付款", "支付", "消费", "消費", "扣款", "支出", "购买", "購買", "已支付", "付款成功", "支付成功", "paid", "debit",
    )

    private val incomeKeywords = listOf(
        "收款", "收钱", "收錢", "到账", "到帳", "入账", "入帳", "退款", "退款成功", "已收款", "received", "credit",
    )

    private val transferKeywords = listOf("转账", "轉帳", "转賬", "轉賬")

    private val merchantLabels = listOf(
        "商户", "商戶", "商家", "店铺", "店鋪", "店家", "卖家", "賣家", "收款方", "收款对象", "收款對象",
    )

    private val counterpartyLabels = listOf(
        "收款方", "收款对象", "收款對象", "付款方", "付款对象", "付款對象", "付款人", "收款人", "对方", "對方",
    )

    /** 「买了什么」：商品 / 宝贝名称。 */
    private val productLabels = listOf(
        "商品名称", "商品名稱", "商品名", "商品标题", "商品標題", "商品", "宝贝", "寶貝",
    )

    fun parse(
        packageName: String,
        texts: List<String>,
        postedAtMillis: Long,
    ): ScreenBillDraft? {
        val nodes = texts.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (nodes.isEmpty()) return null
        val merged = nodes.joinToString(" ").trim()
        if (merged.isBlank()) return null

        val amountHit = extractAmount(merged) ?: return null
        if (!looksLikeBill(merged)) return null

        val source = NotificationParser.detectSource(packageName) ?: "unknown"
        val sourceLabel = CaptureLinking.sourceLabel(source)
        val entryType = inferEntryType(merged)
        val merchant = extractLabelled(nodes, merchantLabels)
        val product = extractLabelled(nodes, productLabels)
        val counterparty = extractLabelled(nodes, counterpartyLabels)
        val occurredAtMillis = extractOccurredAtMillis(merged, postedAtMillis) ?: postedAtMillis
        val signature = buildSignature(packageName, merged, amountHit.amount, occurredAtMillis)
        val confidence = inferConfidence(
            currencyMarked = amountHit.currencyMarked,
            hasBillKeyword = true,
            merchant = merchant,
            counterparty = counterparty,
        )
        val isoTime = Instant.ofEpochMilli(postedAtMillis)
            .atOffset(ZoneOffset.UTC)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val summary = buildString {
            append("页面识别 · ")
            append(sourceLabel)
            if (merchant.isNotBlank()) {
                append(" · ")
                append(merchant)
            }
            if (product.isNotBlank()) {
                append(" · ")
                append(product)
            }
        }

        return ScreenBillDraft(
            id = buildId(signature),
            packageName = packageName,
            source = source,
            sourceLabel = sourceLabel,
            title = merchant.ifBlank { sourceLabel },
            merchant = merchant.ifBlank { "未识别商户" },
            product = product,
            counterpartyName = counterparty,
            rawText = merged.take(420),
            scenario = "screenBill",
            detailSummary = summary.take(420),
            amount = amountHit.amount,
            entryType = entryType,
            channel = inferChannel(source),
            capturedAt = isoTime,
            capturedAtMillis = postedAtMillis,
            postedAtMillis = occurredAtMillis,
            confidence = confidence,
            defaultCategoryId = if (entryType == "transfer") "transfer" else "daily",
            profileId = 0,
            mergeKey = signature,
            signature = signature,
            userConfirmed = false,
        )
    }

    private data class AmountHit(val amount: Double, val currencyMarked: Boolean)

    private fun extractAmount(text: String): AmountHit? {
        amountRegexes.forEachIndexed { index, regex ->
            val match = regex.find(text) ?: return@forEachIndexed
            val raw = match.groupValues.getOrNull(1)?.replace(",", "") ?: return@forEachIndexed
            val value = raw.toDoubleOrNull() ?: return@forEachIndexed
            if (value > 0) {
                return AmountHit(value, currencyMarked = index < MARKED_REGEX_COUNT)
            }
        }
        return null
    }

    private fun looksLikeBill(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return billKeywords.any { lower.contains(it.lowercase(Locale.ROOT)) }
    }

    private fun inferEntryType(text: String): String {
        val lower = text.lowercase(Locale.ROOT)
        if (transferKeywords.any { lower.contains(it.lowercase(Locale.ROOT)) }) return "transfer"
        val income = incomeKeywords.count { lower.contains(it.lowercase(Locale.ROOT)) }
        val expense = expenseKeywords.count { lower.contains(it.lowercase(Locale.ROOT)) }
        return when {
            income > expense -> "income"
            expense > 0 -> "expense"
            income > 0 -> "income"
            else -> "expense"
        }
    }

    private fun inferChannel(source: String): String = when (source) {
        "wechat" -> "wechatPay"
        "alipay" -> "alipay"
        "googlePay" -> "googlePay"
        "bank" -> "bankCard"
        else -> "other"
    }

    private fun inferConfidence(
        currencyMarked: Boolean,
        hasBillKeyword: Boolean,
        merchant: String,
        counterparty: String,
    ): Double {
        var score = 0.35
        score += if (currencyMarked) 0.25 else 0.10
        if (hasBillKeyword) score += 0.15
        if (merchant.isNotBlank()) score += 0.12
        if (counterparty.isNotBlank()) score += 0.08
        return score.coerceIn(0.0, 0.98)
    }

    /**
     * 字段提取：字段名和值可能是同一个节点（"商户：麦当劳"），
     * 也可能是相邻两个节点（"商户" / "麦当劳"）——两种都覆盖。
     */
    private fun extractLabelled(nodes: List<String>, labels: List<String>): String {
        for (index in nodes.indices) {
            val node = nodes[index].trim()
            if (node.isBlank()) continue
            val isLabelNode = labels.any { matchesLabelNode(node, it) }
            if (isLabelNode) {
                val next = nodes.getOrNull(index + 1)?.trim().orEmpty()
                if (next.isNotBlank() &&
                    next.length <= 36 &&
                    labels.none { matchesLabelNode(next, it) }
                ) {
                    return next
                }
                continue
            }
            for (label in labels) {
                val inline = inlineValue(node, label)
                if (inline != null) return inline
            }
        }
        return ""
    }

    private fun matchesLabelNode(node: String, label: String): Boolean =
        node == label || node == "$label：" || node == "$label:"

    private fun inlineValue(node: String, label: String): String? {
        // 只在带分隔符时才算内联字段，避免「付款方式」被当成「付款方」+值「式」。
        for (form in listOf("$label：", "$label:")) {
            if (node.startsWith(form)) {
                val rest = node.removePrefix(form).trim()
                if (rest.isNotBlank() && rest.length <= 36) return rest
            }
        }
        return null
    }

    private val dateTimeWithYear = Regex(
        """(\d{4})[-/年.](\d{1,2})[-/月.](\d{1,2})日?\s*(\d{1,2}):(\d{2})(?::(\d{2}))?""",
    )

    private val dateTimeNoYear = Regex(
        """(?:^|[^\d])(\d{1,2})[-/月.](\d{1,2})日?\s*(\d{1,2}):(\d{2})(?::(\d{2}))?""",
    )

    private val relativeDayTime = Regex(
        """(今天|今日|昨天|昨日)\s*(\d{1,2}):(\d{2})(?::(\d{2}))?""",
    )

    /**
     * 从账单页文字里读【交易时间】，返回本机时区的毫秒时刻；读不到返回 null。
     * 支持：2026-10-12 12:34[:56]、2026年10月12日 12:34、10月12日 12:34、今天/昨天 12:34。
     */
    private fun extractOccurredAtMillis(text: String, nowMillis: Long): Long? {
        val zone = ZoneId.systemDefault()
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)

        dateTimeWithYear.find(text)?.let { match ->
            buildLocal(
                year = match.groupValues[1].toIntOrNull(),
                month = match.groupValues[2].toIntOrNull(),
                day = match.groupValues[3].toIntOrNull(),
                hour = match.groupValues[4].toIntOrNull(),
                minute = match.groupValues[5].toIntOrNull(),
                second = match.groupValues[6].toIntOrNull() ?: 0,
            )?.let { return it.atZone(zone).toInstant().toEpochMilli() }
        }

        relativeDayTime.find(text)?.let { match ->
            val dayWord = match.groupValues[1]
            val base = if (dayWord == "昨天" || dayWord == "昨日") {
                now.minusDays(1)
            } else {
                now
            }
            buildLocal(
                year = base.year,
                month = base.monthValue,
                day = base.dayOfMonth,
                hour = match.groupValues[2].toIntOrNull(),
                minute = match.groupValues[3].toIntOrNull(),
                second = match.groupValues[4].toIntOrNull() ?: 0,
            )?.let { return it.atZone(zone).toInstant().toEpochMilli() }
        }

        dateTimeNoYear.find(text)?.let { match ->
            val month = match.groupValues[1].toIntOrNull()
            val day = match.groupValues[2].toIntOrNull()
            val hour = match.groupValues[3].toIntOrNull()
            val minute = match.groupValues[4].toIntOrNull()
            val second = match.groupValues[5].toIntOrNull() ?: 0
            var candidate = buildLocal(now.year, month, day, hour, minute, second)
            if (candidate != null &&
                candidate.atZone(zone).toInstant().toEpochMilli() > nowMillis + DAY_MS
            ) {
                // 没有年份、又明显在未来：按去年算（跨年时的“12月31日”）。
                candidate = buildLocal(now.year - 1, month, day, hour, minute, second)
            }
            candidate?.let { return it.atZone(zone).toInstant().toEpochMilli() }
        }

        return null
    }

    private fun buildLocal(
        year: Int?,
        month: Int?,
        day: Int?,
        hour: Int?,
        minute: Int?,
        second: Int,
    ): LocalDateTime? {
        if (year == null || month == null || day == null || hour == null || minute == null) {
            return null
        }
        return try {
            LocalDateTime.of(year, month, day, hour, minute, second)
        } catch (_: DateTimeException) {
            null
        }
    }

    private fun buildSignature(
        packageName: String,
        merged: String,
        amount: Double,
        occurredAtMillis: Long,
    ): String {
        val normalized = merged
            .replace(Regex("""\d+"""), "#")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(80)
        // 带上金额与交易时间（分钟）：同页面重新识别是同一笔（同签名），
        // 同商户同金额的另一次消费时间不同 → 不同签名，不会互相覆盖。
        return "$packageName|$normalized|a=$amount|t=${occurredAtMillis / 60_000}"
    }

    private fun buildId(signature: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(signature.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return digest.take(24)
    }
}
