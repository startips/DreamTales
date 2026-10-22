package com.bedtime.dreamtales.ui.navigation

sealed class Screen(val route: String) {
    object Splash : Screen("splash")
    object Home : Screen("home")
    object RecordVoice : Screen("record_voice")
    object SleepPlayer : Screen("sleep_player")
}
