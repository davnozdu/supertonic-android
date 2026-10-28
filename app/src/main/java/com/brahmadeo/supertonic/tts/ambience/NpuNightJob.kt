package com.brahmadeo.supertonic.tts.ambience

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/** «Готовить NPU ночью»: when the phone charges and is idle (the system decides, usually at night), the NPU graphs
 * not compiled yet are compiled in the generator's foreground service (a medium model part takes up to ~20 min,
 * longer than a job may run). Unplugging stops it between parts; the parts made stay, the rest waits for the next night. */
class NpuNightJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        // A book being prepared (it makes its sounds) or other generator work: later, never beside it.
        val busy = com.brahmadeo.supertonic.tts.books.prepare.BookPreparation.state.value.running || AmbienceModel.busy
        if (busy || !AmbienceGeneration.startNpuCompile(this)) jobFinished(params, true)
        else jobFinished(params, false)
        return false
    }
    override fun onStopJob(params: JobParameters?) = true

    companion object {
        private const val ID = 4209
        fun charging(context: Context): Boolean {
            val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            return status != 0
        }
        /** Scheduled while the switch is on and a graph is waiting; cancelled otherwise. Cheap: call it freely. */
        /** True when the switch is on and a graph waits to be compiled (the night book queue lets it go first). */
        fun pending(context: Context) = AmbienceSettings.npuNight(context.getSharedPreferences("SupertonicPrefs", 0)) &&
            (AmbienceModel.npuUncompiled(context, false) || AmbienceModel.npuUncompiled(context, true))
        fun schedule(context: Context) {
            val jobs = context.getSystemService(JobScheduler::class.java) ?: return
            val prefs = context.getSharedPreferences("SupertonicPrefs", 0)
            val wanted = AmbienceSettings.npuNight(prefs) &&
                (AmbienceModel.npuUncompiled(context, false) || AmbienceModel.npuUncompiled(context, true))
            if (!wanted) { jobs.cancel(ID); return }
            if (jobs.getPendingJob(ID) != null) return
            jobs.schedule(JobInfo.Builder(ID, ComponentName(context, NpuNightJob::class.java))
                .setRequiresCharging(true).setRequiresDeviceIdle(true).build())
        }
    }
}
