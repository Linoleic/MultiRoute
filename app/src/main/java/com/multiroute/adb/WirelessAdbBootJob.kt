package com.multiroute.adb

import android.app.job.*
import android.content.*
import android.os.PersistableBundle
import android.os.SystemClock
import kotlinx.coroutines.*

/** One bounded recovery window per boot; no permanent polling service. */
class WirelessAdbBootJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var task: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        task = scope.launch {
            val result = try {
                WirelessAdbController.restore(applicationContext, params.extras.getLong("deadline"))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                android.util.Log.e("MultiRoute-AdbBoot", "Recovery task failed", error)
                AdbActionResult.FAILED
            }
            android.util.Log.i("MultiRoute-AdbBoot", "Recovery result: $result")
            jobFinished(params, result == AdbActionResult.WAIT_NETWORK || result == AdbActionResult.WAIT_UNLOCK)
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        task?.cancel()
        return SystemClock.elapsedRealtime() < params.extras.getLong("deadline")
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    companion object {
        private const val ID = 72401
        fun schedule(context: Context) {
            if (!WirelessAdbController.policy(context).enabled) return
            val extras = PersistableBundle().apply { putLong("deadline", SystemClock.elapsedRealtime() + 600_000) }
            context.getSystemService(JobScheduler::class.java).schedule(
                JobInfo.Builder(ID, ComponentName(context, WirelessAdbBootJob::class.java))
                    .setMinimumLatency(3_000)
                    .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).setExtras(extras).build())
        }
        fun cancel(context: Context) { context.getSystemService(JobScheduler::class.java).cancel(ID) }
    }
}

class WirelessAdbBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) WirelessAdbBootJob.schedule(context)
    }
}
