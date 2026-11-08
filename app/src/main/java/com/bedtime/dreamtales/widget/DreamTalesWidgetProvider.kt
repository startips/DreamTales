package com.bedtime.dreamtales.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.bedtime.dreamtales.MainActivity
import com.bedtime.dreamtales.R
import com.bedtime.dreamtales.core.widget.WidgetProtocol
import com.bedtime.dreamtales.core.widget.WidgetStorySelector
import com.bedtime.dreamtales.data.repository.StoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 桌面小组件：展示今夜最值得听的童话，并支持一键直达伴读。
 */
class DreamTalesWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val story = runCatching {
                    StoryRepository(context).getAllStoriesSync().let(WidgetStorySelector::pick)
                }.getOrNull()

                appWidgetIds.forEach { widgetId ->
                    val views = RemoteViews(context.packageName, R.layout.widget_dreamtales)
                    views.setTextViewText(R.id.widget_story_title, WidgetStorySelector.title(story))
                    views.setTextViewText(R.id.widget_story_subtitle, WidgetStorySelector.subtitle(story))
                    views.setTextViewText(R.id.widget_play_button, WidgetStorySelector.playButtonLabel(story))

                    val playIntent = Intent(context, MainActivity::class.java).apply {
                        action = ACTION_WIDGET_PLAY
                        putExtra(EXTRA_STORY_ID, story?.id ?: 0L)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    val playPendingIntent = PendingIntent.getActivity(
                        context,
                        (story?.id ?: 0L).toInt(),
                        playIntent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    views.setOnClickPendingIntent(R.id.widget_play_button, playPendingIntent)

                    val openIntent = Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    val openPendingIntent = PendingIntent.getActivity(
                        context,
                        9001,
                        openIntent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    views.setOnClickPendingIntent(R.id.widget_root, openPendingIntent)

                    appWidgetManager.updateAppWidget(widgetId, views)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_WIDGET_PLAY = WidgetProtocol.ACTION_WIDGET_PLAY
        const val EXTRA_STORY_ID = WidgetProtocol.EXTRA_STORY_ID

        /**
         * 故事库发生变化时主动刷新桌面小组件。
         */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, DreamTalesWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            if (ids.isNotEmpty()) {
                val intent = Intent(context, DreamTalesWidgetProvider::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                }
                context.sendBroadcast(intent)
            }
        }
    }
}
