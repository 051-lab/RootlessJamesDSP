package me.timschneeberger.rootlessjamesdsp.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder

abstract class BaseAudioProcessorService : Service() {
    private val binder = LocalBinder(this)

    class LocalBinder internal constructor(service: BaseAudioProcessorService) : Binder() {
        @Volatile
        private var boundService: BaseAudioProcessorService? = service

        val service: BaseAudioProcessorService
            get() = checkNotNull(boundService) { "Service has been destroyed" }

        internal fun clear() {
            boundService = null
        }
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onCreate() {
        activeServices++
        super.onCreate()
    }

    override fun onDestroy() {
        binder.clear()
        activeServices--
        super.onDestroy()
    }

    companion object {
        var activeServices: Int = 0
            private set
    }
}
