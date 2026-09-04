package com.example.kochakdns

import java.util.concurrent.ConcurrentHashMap

/**
 * کش DNS سمت کلاینت با TTL (پیاده‌سازی اصولی):
 *
 * - کلید کش، پیام پرس‌وجوی DNS بدون ۲ بایت ID است (چون ID هر کوئری عوض می‌شود).
 * - فقط پاسخ‌های معتبر (rcode=0، بدون پرچم Truncated، با حداقل یک Answer) کش می‌شوند.
 * - TTL از خودِ پاسخ DNS خوانده می‌شود (اولین رکورد Answer) و بین ۱۰ تا ۳۰۰ ثانیه
 *   محدود می‌شود؛ اگر قابل خواندن نبود، ۶۰ ثانیه به‌عنوان fallback استفاده می‌شود.
 * - هنگام پاسخ از کش، شناسه‌ی تراکنش (ID) با ID کوئری فعلی جایگزین می‌شود تا
 *   کلاینت پاسخ را برای همان پرس‌وجو بپذیرد.
 */
class DnsCache(private val maxEntries: Int = 512) {

    private data class Entry(val response: ByteArray, val expiresAt: Long)

    private val map = ConcurrentHashMap<String, Entry>()

    /** کلید کش: کل پیام کوئری به‌جز ۲ بایت ID. */
    private fun key(query: ByteArray): String {
        val sb = StringBuilder(query.size - 2)
        for (i in 2 until query.size) {
            sb.append((query[i].toInt() and 0xFF).toChar())
        }
        return sb.toString()
    }

    /** پاسخ کش‌شده برای این کوئری؛ null یعنی وجود ندارد یا منقضی شده. ID پاسخ با ID کوئری هماهنگ می‌شود. */
    fun get(query: ByteArray, queryId: Int): ByteArray? {
        val k = key(query)
        val e = map[k] ?: return null
        if (System.currentTimeMillis() > e.expiresAt) {
            map.remove(k)
            return null
        }
        val out = e.response.copyOf()
        out[0] = ((queryId shr 8) and 0xFF).toByte()
        out[1] = (queryId and 0xFF).toByte()
        return out
    }

    /** ذخیره‌ی پاسخ (فقط اگر قابل کش باشد). */
    fun put(query: ByteArray, response: ByteArray) {
        if (!isCacheable(response)) return
        val ttl = readTtl(response)
        if (ttl <= 0) return

        // اگر پر شد، ابتدا منقضی‌شده‌ها را پاک کن
        if (map.size >= maxEntries) {
            val now = System.currentTimeMillis()
            map.entries.removeIf { now > it.value.expiresAt }
        }
        map[key(query)] = Entry(response.copyOf(), System.currentTimeMillis() + ttl * 1000L)
    }

    fun clear() {
        map.clear()
    }

    private fun isCacheable(response: ByteArray): Boolean {
        if (response.size < 12) return false
        val flags1 = response[2].toInt() and 0xFF
        val flags2 = response[3].toInt() and 0xFF
        val qdCount = ((response[4].toInt() and 0xFF) shl 8) or (response[5].toInt() and 0xFF)
        val anCount = ((response[6].toInt() and 0xFF) shl 8) or (response[7].toInt() and 0xFF)
        val rcode = flags2 and 0x0F
        val truncated = (flags1 and 0x02) != 0
        return rcode == 0 && !truncated && qdCount >= 1 && anCount >= 1
    }

    /** خواندن TTL اولین رکورد Answer؛ در صورت خطا ۶۰ ثانیه. */
    private fun readTtl(response: ByteArray): Long {
        return try {
            var off = 12

            // رد کردن سوالات
            var qd = ((response[4].toInt() and 0xFF) shl 8) or (response[5].toInt() and 0xFF)
            while (qd > 0 && off < response.size) {
                off = skipName(response, off)
                off += 4 // QTYPE + QCLASS
                qd--
            }

            // خواندن TTL اولین Answer
            val an = ((response[6].toInt() and 0xFF) shl 8) or (response[7].toInt() and 0xFF)
            var i = 0
            while (i < an && off + 10 <= response.size) {
                off = skipName(response, off)
                if (off + 10 > response.size) break
                val ttl = ((response[off + 4].toLong() and 0xFF) shl 24) or
                        ((response[off + 5].toLong() and 0xFF) shl 16) or
                        ((response[off + 6].toLong() and 0xFF) shl 8) or
                        (response[off + 7].toLong() and 0xFF)
                if (ttl > 0) return ttl.coerceIn(10, 300)
                val rdlen = ((response[off + 8].toInt() and 0xFF) shl 8) or (response[off + 9].toInt() and 0xFF)
                off += 10 + rdlen
                i++
            }
            60
        } catch (_: Exception) {
            60
        }
    }

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
}
