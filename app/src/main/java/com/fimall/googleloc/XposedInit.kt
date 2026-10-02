package com.fimall.googleloc

import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.fimall.googleloc.data.Prefs
import com.fimall.googleloc.hook.LocationHooks
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * LibXposed 102 entry. Declared in META-INF/xposed/java_init.list.
 * Scope is in META-INF/xposed/scope.list (dynamic, user-editable).
 */
class XposedInit : XposedModule() {

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, TAG, "module loaded in ${param.processName} api=$apiVersion")
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onPackageLoaded(param: PackageLoadedParam) {
        // Pre-init only; actual hooking is in onPackageReady where classes are ready.
        if (param.packageName !in TARGETS) return
        log(Log.DEBUG, TAG, "package loaded: ${param.packageName} first=${param.isFirstPackage}")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!param.isFirstPackage) return
        if (param.packageName !in TARGETS) return
        try {
            val prefs: SharedPreferences = try {
                getRemotePreferences(Prefs.FILE)
            } catch (t: Throwable) {
                log(Log.WARN, TAG, "getRemotePreferences failed, fail-open: $t")
                return
            }
            LocationHooks.install(this, param.packageName, param.classLoader, prefs)
            log(Log.INFO, TAG, "hooks installed in ${param.packageName}")
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "install failed in ${param.packageName}", t)
        }
    }

    companion object {
        private const val TAG = "GoogleLoc"
        val TARGETS = setOf(
            "com.android.chrome",
            "com.google.android.gms",
            "com.android.vending",
            "com.google.android.apps.maps",
            "com.google.android.googlequicksearchbox",
            "com.google.android.gm",
            "com.google.android.youtube",
            "com.google.android.apps.photos",
        )
    }
}
