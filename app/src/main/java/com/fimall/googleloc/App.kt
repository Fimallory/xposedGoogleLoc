package com.fimall.googleloc

import android.app.Application
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet

class App : Application(), XposedServiceHelper.OnServiceListener {

    interface ServiceStateListener {
        fun onServiceStateChanged(service: XposedService?)
    }

    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        mService = service
        drainPendingWrites(service)
        notifyAllChanged(service)
    }

    override fun onServiceDied(service: XposedService) {
        if (mService === service) {
            mService = null
            notifyAllChanged(null)
        }
    }

    companion object {
        @Volatile private var mService: XposedService? = null
        private val listeners = CopyOnWriteArraySet<ServiceStateListener>()
        private val pendingWrites = mutableListOf<(XposedService) -> Unit>()

        fun serviceOrNull(): XposedService? = mService

        fun addServiceStateListener(l: ServiceStateListener, notifyNow: Boolean = true) {
            listeners.add(l)
            if (notifyNow) l.onServiceStateChanged(mService)
        }

        fun removeServiceStateListener(l: ServiceStateListener) {
            listeners.remove(l)
        }

        fun enqueueWrite(block: (XposedService) -> Unit) {
            val s = mService
            if (s != null) {
                try { block(s) } catch (_: Throwable) { }
            } else {
                synchronized(pendingWrites) { pendingWrites.add(block) }
            }
        }

        private fun drainPendingWrites(service: XposedService) {
            val copy: List<(XposedService) -> Unit>
            synchronized(pendingWrites) {
                if (pendingWrites.isEmpty()) return
                copy = pendingWrites.toList()
                pendingWrites.clear()
            }
            for (block in copy) try { block(service) } catch (_: Throwable) { }
        }

        private fun notifyAllChanged(s: XposedService?) {
            for (l in listeners) try { l.onServiceStateChanged(s) } catch (_: Throwable) { }
        }
    }
}
