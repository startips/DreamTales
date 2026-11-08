package com.bedtime.dreamtales.core.widget

/**
 * 桌面小组件与 Activity 之间的轻量协议。
 */
object WidgetProtocol {

    const val ACTION_WIDGET_PLAY = "com.bedtime.dreamtales.action.WIDGET_PLAY"
    const val EXTRA_STORY_ID = "com.bedtime.dreamtales.extra.WIDGET_STORY_ID"

    fun extractPlayStoryId(action: String?, storyId: Long): Long? {
        if (action != ACTION_WIDGET_PLAY) return null
        return storyId.takeIf { it > 0L }
    }
}
