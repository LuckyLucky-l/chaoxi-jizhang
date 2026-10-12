package com.aline.jier.jier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BillPageParserTest {
    @Test
    fun `识别支付宝账单详情页的一笔支出`() {
        val draft = BillPageParser.parse(
            packageName = "com.eg.android.AlipayGphone",
            texts = listOf("交易详情", "付款金额", "¥38.00", "商户", "麦当劳(成都店)", "付款方式", "余额宝"),
            postedAtMillis = 1_700_000_000_000,
        )

        assertNotNull(draft)
        assertEquals("alipay", draft?.source)
        assertEquals(38.00, draft?.amount ?: 0.0, 0.001)
        assertEquals("expense", draft?.entryType)
        assertEquals("麦当劳(成都店)", draft?.merchant)
        assertTrue((draft?.confidence ?: 0.0) >= 0.5)
    }

    @Test
    fun `识别微信转账页为转账并取到金额`() {
        val draft = BillPageParser.parse(
            packageName = "com.tencent.mm",
            texts = listOf("微信支付", "你已成功转账￥200.00给妈妈"),
            postedAtMillis = 1_700_000_000_000,
        )

        assertNotNull(draft)
        assertEquals("wechat", draft?.source)
        assertEquals("transfer", draft?.entryType)
        assertEquals(200.00, draft?.amount ?: 0.0, 0.001)
        assertEquals("transfer", draft?.defaultCategoryId)
    }

    @Test
    fun `金额夹在中间的转账文案也能识别`() {
        val draft = BillPageParser.parse(
            packageName = "com.tencent.mm",
            texts = listOf("转账 ￥88.80 给夏曦晨光"),
            postedAtMillis = 1_700_000_000_000,
        )

        assertNotNull(draft)
        assertEquals(88.80, draft?.amount ?: 0.0, 0.001)
        assertEquals("transfer", draft?.entryType)
    }

    @Test
    fun `无关页面（聊天）不产生草稿`() {
        val draft = BillPageParser.parse(
            packageName = "com.tencent.mm",
            texts = listOf("今天天气不错", "晚上吃什么"),
            postedAtMillis = 1_700_000_000_000,
        )

        assertNull(draft)
    }

    @Test
    fun `只有订单号没有金额的页面不产生草稿`() {
        val draft = BillPageParser.parse(
            packageName = "com.taobao.taobao",
            texts = listOf("订单号 1234567890", "客服电话 4001234567"),
            postedAtMillis = 1_700_000_000_000,
        )

        assertNull(draft)
    }

    @Test
    fun `带标签相邻节点的商户字段能被取到`() {
        val draft = BillPageParser.parse(
            packageName = "com.jingdong.app.mall",
            texts = listOf("京东", "订单金额：¥120.00", "商家", "成都数码配件专营店"),
            postedAtMillis = 1_700_000_000_000,
        )

        assertNotNull(draft)
        assertEquals("成都数码配件专营店", draft?.merchant)
        assertEquals(120.00, draft?.amount ?: 0.0, 0.001)
    }

    @Test
    fun `页面签名包含金额，两笔不同金额不互相覆盖`() {
        val first = BillPageParser.parse(
            packageName = "com.eg.android.AlipayGphone",
            texts = listOf("付款成功", "¥10.00", "商户：罗森"),
            postedAtMillis = 1_700_000_000_000,
        )
        val second = BillPageParser.parse(
            packageName = "com.eg.android.AlipayGphone",
            texts = listOf("付款成功", "¥20.00", "商户：罗森"),
            postedAtMillis = 1_700_000_060_000,
        )

        assertNotNull(first)
        assertNotNull(second)
        assertTrue(first?.signature != second?.signature)
    }

    @Test
    fun `从账单页读出交易时间作为记账时间`() {
        val postedAt = 1_800_000_000_000L
        val draft = BillPageParser.parse(
            packageName = "com.tencent.mm",
            texts = listOf("微信支付", "付款成功 ¥38.00", "商户：麦当劳", "2026-10-12 12:34:56"),
            postedAtMillis = postedAt,
        )

        assertNotNull(draft)
        val zone = java.time.ZoneId.systemDefault()
        val expected = java.time.LocalDateTime.of(2026, 10, 12, 12, 34, 56)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
        assertEquals(expected, draft?.postedAtMillis)
        // 识别时刻另行记录，用于草稿保留期。
        assertEquals(postedAt, draft?.capturedAtMillis)
    }

    @Test
    fun `读不到交易时间时退化为识别时刻`() {
        val postedAt = 1_800_000_000_000L
        val draft = BillPageParser.parse(
            packageName = "com.eg.android.AlipayGphone",
            texts = listOf("付款成功", "¥12.00", "商户：罗森"),
            postedAtMillis = postedAt,
        )

        assertNotNull(draft)
        assertEquals(postedAt, draft?.postedAtMillis)
        assertEquals(postedAt, draft?.capturedAtMillis)
    }
}
