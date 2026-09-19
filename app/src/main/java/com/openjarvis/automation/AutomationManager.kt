package com.openjarvis.automation

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.TimeUnit

class AutomationManager(private val context: Context) {
    private val dao = AutomationDB.getInstance(context).automationDao()
    private val _automationsFlow = MutableStateFlow<List<Automation>>(emptyList())
    val automationsFlow: StateFlow<List<Automation>> = _automationsFlow

    suspend fun loadAutomations() {
        _automationsFlow.value = dao.getAll().map { it.toDomain() }
    }

    suspend fun createAutomation(automation: Automation): String = withContext(Dispatchers.IO) {
        dao.insert(automation.toEntity())
        scheduleAutomation(automation)
        _automationsFlow.value = dao.getAll().map { it.toDomain() }
        automation.id
    }

    suspend fun updateAutomation(automation: Automation) = withContext(Dispatchers.IO) {
        dao.update(automation.toEntity())
        cancelAutomation(automation.id)
        if (automation.enabled) scheduleAutomation(automation)
        _automationsFlow.value = dao.getAll().map { it.toDomain() }
    }

    suspend fun deleteAutomation(id: String) = withContext(Dispatchers.IO) {
        cancelAutomation(id)
        dao.delete(id)
        _automationsFlow.value = dao.getAll().map { it.toDomain() }
    }

    suspend fun toggleAutomation(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        val current = dao.getById(id)?.toDomain() ?: return@withContext
        val updated = current.copy(enabled = enabled)
        dao.update(updated.toEntity())
        if (enabled) scheduleAutomation(updated) else cancelAutomation(id)
        _automationsFlow.value = dao.getAll().map { it.toDomain() }
    }

    suspend fun runNow(id: String) = withContext(Dispatchers.IO) {
        dao.getById(id)?.toDomain()?.let { executeAutomation(it) }
    }

    private suspend fun scheduleAutomation(automation: Automation) {
        val constraints = Constraints.Builder().setRequiresBatteryNotLow(false).build()
        val inputData = workDataOf("automation_id" to automation.id, "automation_command" to automation.command)
        val request = when (val schedule = automation.schedule) {
            is AutomationSchedule.Daily -> PeriodicWorkRequestBuilder<AutomationWorker>(24, TimeUnit.HOURS, 15, TimeUnit.MINUTES)
                .setConstraints(constraints).setInputData(inputData)
                .setInitialDelay(calculateDelay(schedule.hour, schedule.minute), TimeUnit.MILLISECONDS)
                .addTag(automation.id).build()
            is AutomationSchedule.Weekly -> PeriodicWorkRequestBuilder<AutomationWorker>(7, TimeUnit.DAYS, 15, TimeUnit.MINUTES)
                .setConstraints(constraints).setInputData(inputData)
                .setInitialDelay(calculateWeeklyDelay(schedule.dayOfWeek, schedule.hour, schedule.minute), TimeUnit.MILLISECONDS)
                .addTag(automation.id).build()
            is AutomationSchedule.Interval -> PeriodicWorkRequestBuilder<AutomationWorker>(
                schedule.intervalMs.coerceAtLeast(15 * 60 * 1000L), TimeUnit.MILLISECONDS, 1, TimeUnit.MINUTES
            ).setConstraints(constraints).setInputData(inputData).addTag(automation.id).build()
            is AutomationSchedule.Once -> {
                val delay = schedule.atMs - System.currentTimeMillis()
                if (delay <= 0) return
                OneTimeWorkRequestBuilder<AutomationWorker>().setConstraints(constraints).setInputData(inputData)
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS).addTag(automation.id).build()
            }
        }
        WorkManager.getInstance(context).enqueueUniqueWork(automation.id, ExistingWorkPolicy.REPLACE, request)
    }

    private fun cancelAutomation(id: String) {
        WorkManager.getInstance(context).cancelAllWorkByTag(id)
    }

    private suspend fun executeAutomation(automation: Automation) {
        val updated = automation.copy(
            lastRun = System.currentTimeMillis(),
            lastResult = "success",
            runCount = automation.runCount + 1
        )
        dao.update(updated.toEntity())
        _automationsFlow.value = dao.getAll().map { it.toDomain() }
    }

    private fun calculateDelay(targetHour: Int, targetMinute: Int): Long {
        val cal = java.util.Calendar.getInstance()
        val now = cal.timeInMillis
        cal.set(java.util.Calendar.HOUR_OF_DAY, targetHour)
        cal.set(java.util.Calendar.MINUTE, targetMinute)
        cal.set(java.util.Calendar.SECOND, 0)
        var delay = cal.timeInMillis - now
        if (delay < 0) delay += 24 * 60 * 60 * 1000
        return delay
    }

    private fun calculateWeeklyDelay(dayOfWeek: Int, targetHour: Int, targetMinute: Int): Long {
        val cal = java.util.Calendar.getInstance()
        val now = cal.timeInMillis
        cal.set(java.util.Calendar.DAY_OF_WEEK, dayOfWeek)
        cal.set(java.util.Calendar.HOUR_OF_DAY, targetHour)
        cal.set(java.util.Calendar.MINUTE, targetMinute)
        cal.set(java.util.Calendar.SECOND, 0)
        var delay = cal.timeInMillis - now
        if (delay < 0) delay += 7 * 24 * 60 * 60 * 1000
        return delay
    }

    fun parseSchedule(input: String): AutomationSchedule? {
        val lower = input.lowercase()
        val daily = Regex("""every day at (\d{1,2})(?::(\d{2}))?\s*(am|pm)?""").find(lower)
        if (daily != null) {
            var hour = daily.groupValues[1].toInt()
            val minute = daily.groupValues[2].toIntOrNull() ?: 0
            if (daily.groupValues[3] == "pm" && hour != 12) hour += 12
            if (daily.groupValues[3] == "am" && hour == 12) hour = 0
            return AutomationSchedule.Daily(hour, minute)
        }
        val interval = Regex("""every (\d+)\s*(minute|hour|day)s?""").find(lower)
        if (interval != null) {
            val value = interval.groupValues[1].toLong()
            val ms = when (interval.groupValues[2]) {
                "minute" -> value * 60_000L
                "hour" -> value * 3_600_000L
                else -> value * 86_400_000L
            }
            return AutomationSchedule.Interval(ms)
        }
        return null
    }

    private fun AutomationEntity.toDomain(): Automation {
        val schedule = when (scheduleType) {
            "daily" -> AutomationSchedule.Daily(scheduleHour, scheduleMinute)
            "weekly" -> AutomationSchedule.Weekly(scheduleDayOfWeek, scheduleHour, scheduleMinute)
            "once" -> AutomationSchedule.Once(scheduleIntervalMs)
            else -> AutomationSchedule.Interval(scheduleIntervalMs.coerceAtLeast(60_000L))
        }
        return Automation(id, name, command, schedule, enabled, lastRun, lastResult, runCount)
    }

    private fun Automation.toEntity(): AutomationEntity {
        val (type, hour, minute, day, interval) = when (val s = schedule) {
            is AutomationSchedule.Daily -> listOf("daily", s.hour, s.minute, 0, 0L)
            is AutomationSchedule.Weekly -> listOf("weekly", s.hour, s.minute, s.dayOfWeek, 0L)
            is AutomationSchedule.Interval -> listOf("interval", 0, 0, 0, s.intervalMs)
            is AutomationSchedule.Once -> listOf("once", 0, 0, 0, s.atMs)
        }
        return AutomationEntity(id, name, command, type as String, hour as Int, minute as Int, day as Int, interval as Long, enabled, lastRun, lastResult, runCount)
    }

    data class Automation(
        val id: String,
        val name: String,
        val command: String,
        val schedule: AutomationSchedule,
        val enabled: Boolean = true,
        val lastRun: Long? = null,
        val lastResult: String? = null,
        val runCount: Int = 0
    )

    sealed class AutomationSchedule {
        data class Daily(val hour: Int, val minute: Int) : AutomationSchedule()
        data class Weekly(val dayOfWeek: Int, val hour: Int, val minute: Int) : AutomationSchedule()
        data class Interval(val intervalMs: Long) : AutomationSchedule()
        data class Once(val atMs: Long) : AutomationSchedule()
    }
}