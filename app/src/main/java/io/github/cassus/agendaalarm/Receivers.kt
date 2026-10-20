package io.github.cassus.agendaalarm

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract

/** Fires when a scheduled alarm or snooze is due: shows the full-screen alert, then sets the next alarm. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra(Scheduler.EXTRA_TITLE) ?: return
        val begin = intent.getLongExtra(Scheduler.EXTRA_BEGIN, 0)
        intent.getStringExtra(Scheduler.EXTRA_KEY)?.let { Settings(context).markFired(it, System.currentTimeMillis()) }
        AlertService.start(context, title, begin)
        Scheduler.reschedule(context)
    }
}

/** Reboot, app update, time/zone change, or a calendar reminder broadcast: recompute the next alarm. */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Scheduler.reschedule(context)
}

/** Runs when anything in the calendar provider changes, then re-arms itself. */
class CalendarChangeJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Scheduler.reschedule(this)
        return false
    }

    override fun onStopJob(params: JobParameters) = true

    companion object {
        private const val JOB_ID = 1

        fun schedule(context: Context) {
            val js = context.getSystemService(JobScheduler::class.java)
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, CalendarChangeJob::class.java))
                .addTriggerContentUri(JobInfo.TriggerContentUri(CalendarContract.CONTENT_URI, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                .setTriggerContentUpdateDelay(2_000)
                .setTriggerContentMaxDelay(10_000)
                .build()
            js.schedule(job)
        }
    }
}
