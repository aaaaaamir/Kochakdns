package com.example.kochakdns

import java.util.concurrent.ConcurrentHashMap

/**
 * کش DNS سمت کلاینت — پیاده‌سازی اصولی با سه قابلیت:
 *
 * ۱) TTL واقعی: TTL از خودِ پاسخ خوانده می‌شود (برای پاسخ مثبت از رکورد Answer،
 *    برای پاسخ منفی از رکورد SOA مطابق RFC 2308) و در بازه‌ی امن محدود می‌شود.
 * ۲) کش منفی (Negative Caching): پاسخ‌های NXDOMAIN و NODATA هم برای مدت کوتاهی
 *    کش می‌شوند تا دامنه‌های نامعتبر هر بار به سرور نروند.
 * ۳) stale-while-revalidate: وقتی TTL تمام شود ولی هنوز در «پنجره‌ی نرمی» باشیم،
 *    پاسخ قدیمی فوراً سرو می‌شود و سرویس در پس‌زمینه تازه‌سازی‌اش می‌کند — یعنی
 *    کاربر هیچ‌وقت برای پاسخ منتظر شبکه نمی‌ماند.
 *
 * کلید کش، پیام پرس‌وجوی DNS بدون ۲ بایت ID است (چون ID هر کوئری عوض می‌شود)
 * و هنگام پاسخ از کش، ID پاسخ با ID کوئری فعلی هماهنگ می‌شود.
 */
class DnsCache(private val maxEntries: Int = 512) {

    /** نتیجه‌ی خواندن از کش؛ [stale] یعنی TTL تمام شده و باید در پس‌زمینه تازه‌سازی شود. */
    data class CacheResult(val bytes: ByteArray, val stale: Boolean)

    private data class Entry(
        val response: ByteArray,
        val expiresAt: Long,  // انقضای TTL واقعی رکورد
        val staleUntil: Long  // تا چه زمانی می‌توان پاسخ را «نرم» سرو کرد
    )

    private val map = ConcurrentHashMap<String, Entry>()

    companion object {
        // پنجره‌ی نرمی بعد از انقضای TTL: بین ۱۵ تا ۶۰ ثانیه
        private const val STALE_WINDOW_MIN = 15_000L
        private const val STALE_WINDOW_MAX = 60_000L

        // بازه‌ی TTL کش مثبت (رکوردهای عادی)
        private const val POSITIVE_TTL_MIN = 10L
        private const val POSITIVE_TTL_MAX = 300L
        private const val POSITIVE_TTL_FALLBACK = 60L

        // بازه‌ی TTL کش منفی (NXDOMAIN / NODATA)
        private const val NEGATIVE_TTL_MIN = 5L
        private const val NEGATIVE_TTL_MAX = 60L
        private const val NEGATIVE_TTL_FALLBACK = 15L

        private const val TYPE_SOA = 6
    }

    /** کلید کش: کل پیام کوئری به‌جز ۲ بایت ID. عمومی است تا سرویس بتواند
     *  برای جلوگیری از تازه‌سازی تکراریِ همان ورودی از آن استفاده کند. */
    fun keyOf(query: ByteArray): String {
        val sb = StringBuilder(query.size - 2)
        for (i in 2 until query.size) {
            sb.append((query[i].toInt() and 0xFF).toChar())
        }
        return sb.toString()
    }

    /** پاسخ کش‌شده برای این کوئری؛ null یعنی وجود ندارد یا کاملاً منقضی شده. */
    fun get(query: ByteArray, queryId: Int): CacheResult? {
        val k = keyOf(query)
        val e = map[k] ?: return null
        val now = System.currentTimeMillis()
        if (now > e.staleUntil) {
            map.remove(k)
            return null
        }
        val out = e.response.copyOf()
        out[0] = ((queryId shr 8) and 0xFF).toByte()
        out[1] = (queryId and 0xFF).toByte()
        return CacheResult(out, stale = now > e.expiresAt)
    }

    /** ذخیره‌ی پاسخ (فقط اگر قابل کش باشد). */
    fun put(query: ByteArray, response: ByteArray) {
        val ttlSeconds = cacheTtl(response) ?: return
        if (ttlSeconds <= 0) return

        evictIfNeeded()
        val now = System.currentTimeMillis()
        val staleWindow = (ttlSeconds * 1000L).coerceIn(STALE_WINDOW_MIN, STALE_WINDOW_MAX)
        map[keyOf(query)] = Entry(
            response = response.copyOf(),
            expiresAt = now + ttlSeconds * 1000L,
            staleUntil = now + ttlSeconds * 1000L + staleWindow
        )
    }

    fun clear() {
        map.clear()
    }

    /** TTL مناسب برای این پاسخ را برمی‌گرداند؛ null یعنی پاسخ قابل کش نیست. */
    private fun cacheTtl(response: ByteArray): Long? {
        if (response.size < 12) return null
        val flags1 = response[2].toInt() and 0xFF
        val flags2 = response[3].toInt() and 0xFF
        val qdCount = readCount(response, 4)
        val anCount = readCount(response, 6)
        val rcode = flags2 and 0x0F
        val truncated = (flags1 and 0x02) != 0
        if (truncated || qdCount < 1) return null

        return when {
            // پاسخ مثبت: TTL از اولین رکورد Answer
            rcode == 0 && anCount >= 1 -> {
                val ttl = readPositiveTtl(response)
                (if (ttl > 0) ttl else POSITIVE_TTL_FALLBACK)
                    .coerceIn(POSITIVE_TTL_MIN, POSITIVE_TTL_MAX)
            }
            // پاسخ منفی: NXDOMAIN یا NODATA — TTL از رکورد SOA (RFC 2308)
            rcode == 3 || (rcode == 0 && anCount == 0) -> {
                val ttl = readNegativeTtl(response) ?: NEGATIVE_TTL_FALLBACK
                if (ttl <= 0) null else ttl.coerceIn(NEGATIVE_TTL_MIN, NEGATIVE_TTL_MAX)
            }
            // SERVFAIL و بقیه‌ی خطاها کش نمی‌شوند
            else -> null
        }
    }

    private fun readCount(response: ByteArray, offset: Int): Int =
        ((response[offset].toInt() and 0xFF) shl 8) or (response[offset + 1].toInt() and 0xFF)

    /** خواندن TTL اولین رکورد Answer؛ در صورت خطا ۰. */
    private fun readPositiveTtl(response: ByteArray): Long {
        return try {
            var off = 12
            var qd = readCount(response, 4)
            while (qd > 0 && off < response.size) {
                off = skipName(response, off)
                off += 4 // QTYPE + QCLASS
                qd--
            }
            val an = readCount(response, 6)
            var i = 0
            while (i < an && off + 10 <= response.size) {
                off = skipName(response, off)
                if (off + 10 > response.size) break
                val ttl = readU32(response, off + 4)
                if (ttl > 0) return ttl
                val rdlen = readCount(response, off + 8)
                off += 10 + rdlen
                i++
            }
            0
        } catch (_: Exception) {
            0
        }
    }

    /** خواندن TTL رکورد SOA در بخش Authority (برای کش منفی)؛ null یعنی پیدا نشد. */
    private fun readNegativeTtl(response: ByteArray): Long? {
        return try {
            var off = 12
            var qd = readCount(response, 4)
            while (qd > 0 && off < response.size) {
                off = skipName(response, off) + 4
                qd--
            }
            var an = readCount(response, 6)
            while (an > 0 && off < response.size) {
                off = skipName(response, off)
                if (off + 10 > response.size) return null
                off += 10 + readCount(response, off + 8)
                an--
            }
            var ns = readCount(response, 8)
            while (ns > 0 && off < response.size) {
                off = skipName(response, off)
                if (off + 10 > response.size) return null
                val type = readCount(response, off)
                val ttl = readU32(response, off + 4)
                if (type == TYPE_SOA && ttl > 0) return ttl
                off += 10 + readCount(response, off + 8)
                ns--
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun readU32(data: ByteArray, offset: Int): Long =
        ((data[offset].toLong() and 0xFF) shl 24) or
        ((data[offset + 1].toLong() and 0xFF) shl 16) or
        ((data[offset + 2].toLong() and 0xFF) shl 8) or
        (data[offset + 3].toLong() and 0xFF)

    /** عبور از یک Name (با پشتیبانی از compression pointer). */
    private fun skipName(data: ByteArray, off: Int): Int {
        var o = off
        while (o < data.size) {
            val len = data[o].toInt() and 0xFF
            if (len == 0) {
                o++
                break
            }
            if (len and 0xC0 == 0xC0) {
                o += 2 // pointer
                break
            }
            o += 1 + len
        }
        return o
    }

    /** اگر پر شد، اول منقضی‌ها را پاک کن؛ باز هم پر بود یک ورودی حذف می‌شود. */
    private fun evictIfNeeded() {
        if (map.size < maxEntries) return
        val now = System.currentTimeMillis()
        map.entries.removeIf { now > it.value.staleUntil }
        if (map.size >= maxEntries) {
            val it = map.keys.iterator()
            if (it.hasNext()) {
                it.next()
                it.remove()
            }
        }
    }
}
