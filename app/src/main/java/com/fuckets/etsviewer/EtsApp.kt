package com.fuckets.etsviewer

import android.app.Application

/**
 * 初始化日志，并把未捕获异常写进日志文件，避免崩溃后无迹可寻。
 */
class EtsApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            AppLog.e("Crash", "未捕获异常 @线程[${thread.name}]：${throwable.message}", throwable)
            AppLog.flush()
            previous?.uncaughtException(thread, throwable)
        }
        AppLog.i("EtsApp", "Application 初始化完成")
    }
}
