package com.example.kochakdns

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * وظایف شبکه‌ای که باید «به محض شروع برنامه» (در MainActivity، همزمان با
 * اسپلش) آغاز شوند تا وقتی کاربر به صفحه‌ی اصلی می‌رسد، نتیجه از قبل آماده
 * باشد و هیچ تاخیری حس نکند.
 *
 * الگو دقیقاً مثل DnsSyncCoordinator است: یک Deferred ساخته می‌شود و هر کسی
 * که بعداً به آن نیاز دارد، به همان job در حال اجرا ملحق می‌شود (await) —
 * بدون این‌که درخواست تکراری به سرور بزند.
 */
object StartupTasks {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var updateJob: Deferred<UpdateManager.UpdateInfo?>? = null

    @Volatile
    private var announcementJob: Deferred<AnnouncementManager.Announcement?>? = null

    /** شروع (یا ملحق‌شدن به) درخواست بروزرسانی؛ از MainActivity شروع می‌شود. */
    fun startUpdateCheck(context: Context): Deferred<UpdateManager.UpdateInfo?> {
        updateJob?.let { return it }
        val app = context.applicationContext
        val job = scope.async { UpdateManager.fetchInfo(app) }
        updateJob = job
        return job
    }

    /** شروع (یا ملحق‌شدن به) درخواست اطلاعیه؛ از MainActivity شروع می‌شود. */
    fun startAnnouncement(context: Context): Deferred<AnnouncementManager.Announcement?> {
        announcementJob?.let { return it }
        val app = context.applicationContext
        val job = scope.async { AnnouncementManager.fetch(app) }
        announcementJob = job
        return job
    }
}
