package com.sonance.player.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sonance.player.data.Prefs

/**
 * 睡眠定时到点的系统闹钟入口。
 *
 * 倒计时不能只挂在 ViewModel 协程上：用户把界面划掉后 VM 销毁、协程取消，
 * 而前台服务还在播 —— 闹钟由系统持有，到点唤醒本接收器把服务停掉。
 * 进程已被杀时音乐本就停了，这里只负责清掉落盘状态，避免下次启动误恢复。
 */
class SleepTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = Prefs(context.applicationContext)
        prefs.sleepTimerDeadlineElapsed = 0L
        prefs.sleepTimerAfterCurrentAnchor = -1L
        PlaybackService.stopAndReleaseForSleep()
    }
}