package io.github.nasmonitor.app.ui

import android.content.Context

internal object ArchiveTaskStore {
    private const val PREFS = "knowledge_archive_tasks"
    private const val TASK_ID = "task_id"
    private const val SUBMITTED_AT = "submitted_at"

    data class Snapshot(val taskId: String, val submittedAt: Long)

    fun load(context: Context): Snapshot? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getString(TASK_ID, null)?.trim().orEmpty()
        if (id.isBlank()) return null
        return Snapshot(id, prefs.getLong(SUBMITTED_AT, 0L))
    }

    fun save(context: Context, taskId: String, submittedAt: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(TASK_ID, taskId)
            .putLong(SUBMITTED_AT, submittedAt)
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(TASK_ID)
            .remove(SUBMITTED_AT)
            .apply()
    }
}
