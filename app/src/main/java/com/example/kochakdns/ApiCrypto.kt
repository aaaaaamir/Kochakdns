package com.example.kochakdns

import android.util.Base64
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * رمزگشایی پاسخ‌های رمزنگاری‌شده‌ی API (لیست و اطلاعات DNSها).
 *
 * قرارداد با بک‌اند (Cloudflare Worker):
 * - وقتی رمزنگاری در ربات روشن باشد، پاسخ به شکل `ENC1:<base64(iv || ciphertext || tag)>`
 *   برمی‌گردد (AES-256-GCM با کلید مشتق‌شده از PBKDF2-HMAC-SHA256).
 * - وقتی رمزنگاری خاموش باشد، پاسخ JSON ساده است و برنامه مستقیم می‌خواند.
 *
 * این کلاس فقط «تشخیص» می‌کند: اگه بدنه با پیشوند ENC1: شروع شود رمزگشایی می‌کند،
 * وگرنه رشته را دست‌نخورده برمی‌گرداند — پس هم با بک‌اند رمزنگاری‌شده هم بدون آن کار می‌کند.
 *
 * پارامترها (passphrase/salt/iterations/hash) باید دقیقاً با worker.js یکسان باشند.
 *
 * نکته: از PBKDF2-HMAC-SHA1 استفاده می‌شود چون SHA-256 نسخه‌اش فقط از اندروید ۸
 * (API 26) به بعد موجود است و minSdk برنامه ۲۱ است.
 */
object ApiCrypto {

    const val PREFIX = "ENC1:"

    private const val PASSPHRASE = "kochak-dns-shared-secret-v1"
    private const val SALT = "kochakdns::salt::2026"
    private const val ITERATIONS = 10_000
    private const val KEY_BITS = 256
    private const val IV_LENGTH = 12
    private const val TAG_LENGTH_BITS = 128

    /** کلید AES مشتق‌شده؛ فقط یک بار (lazy) محاسبه می‌شود. */
    private val key: ByteArray by lazy {
        val spec = PBEKeySpec(
            PASSPHRASE.toCharArray(),
            SALT.toByteArray(StandardCharsets.UTF_8),
            ITERATIONS,
            KEY_BITS
        )
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
        factory.generateSecret(spec).encoded
    }

    /** آیا این بدنه رمزنگاری‌شده است؟ */
    fun isEncrypted(body: String): Boolean = body.trim().startsWith(PREFIX)

    /**
     * رمزگشایی بدنه؛ اگه رمزنگاری نشده باشد همان رشته برمی‌گردد.
     * @throws Exception اگر بدنه رمزنگاری‌شده بود ولی رمزگشایی ناموفق بود.
     */
    fun decryptIfNeeded(body: String): String {
        val trimmed = body.trim()
        if (!trimmed.startsWith(PREFIX)) return body

        val raw = Base64.decode(trimmed.substring(PREFIX.length), Base64.NO_WRAP)
        require(raw.size >= IV_LENGTH + TAG_LENGTH_BITS / 8) { "payload رمزنگاری‌شده نامعتبر است" }

        val iv = raw.copyOfRange(0, IV_LENGTH)
        val ciphertextWithTag = raw.copyOfRange(IV_LENGTH, raw.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_LENGTH_BITS, iv)
        )
        val plain = cipher.doFinal(ciphertextWithTag)
        return String(plain, StandardCharsets.UTF_8)
    }
}
