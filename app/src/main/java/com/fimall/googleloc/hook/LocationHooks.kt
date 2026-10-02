package com.fimall.googleloc.hook

import android.content.SharedPreferences
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.fimall.googleloc.data.Prefs
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

object LocationHooks {

    private lateinit var module: XposedModule
    private lateinit var prefs: SharedPreferences
    private val installedMethods = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val loggedHits = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val semanticDepth = ThreadLocal.withInitial { 0 }

    private fun depth(): Int = semanticDepth.get() ?: 0
    private fun setDepth(value: Int) = semanticDepth.set(value)

    private data class FakeConfig(
        val latitude: Double,
        val longitude: Double,
        val altitude: Double,
        val accuracy: Float,
    )

    fun install(module: XposedModule, packageName: String, classLoader: ClassLoader, prefs: SharedPreferences) {
        this.module = module
        this.prefs = prefs

        if (packageName == GMS_PACKAGE) {
            installGmsReportingHooks(classLoader)
        } else {
            installConsumerPresentationHooks()
        }
        module.log(Log.INFO, TAG, "hooks installed in $packageName")
    }

    private fun installGmsReportingHooks(loader: ClassLoader) {
        module.log(Log.INFO, TAG, "GMS mode: semantic reporting hooks only; fused delivery remains real")

        val lsr = findClass(loader, LSR_REPORTING_CLASSES)
        if (lsr != null) {
            hookLocationMethods(lsr, setOf("onLocationChanged"), "LSR.onLocationChanged", allLocationMethods = false)
            hookLocationMethods(lsr, emptySet(), "LSR.fallback", allLocationMethods = true, shape = ::isControlledReportingShape)
        }

        val periodic = findClass(loader, PERIODIC_REPORTING_CLASSES)
        if (periodic != null) {
            hookIntentMethod(periodic, "PeriodicLocationReportingIntentOperation.onHandleIntent") { intent ->
                isPeriodicReportingAction(intent)
            }
        }

        val tracking = findClass(loader, TRACKING_CLASSES)
        if (tracking != null) {
            hookLocationMethods(tracking, setOf("d", "m"), "LSR.tracking.primary", allLocationMethods = false)
            hookLocationMethods(tracking, emptySet(), "LSR.tracking.fallback", allLocationMethods = true, shape = ::isControlledReportingShape)
        }

        val ulr = findClass(loader, ULR_CLASSES)
        if (ulr != null) {
            hookLocationMethods(ulr, setOf("d", "e"), "ULR.primary", allLocationMethods = false, shape = ::isUlrReportingShape)
            hookLocationMethods(ulr, emptySet(), "ULR.fallback", allLocationMethods = true, shape = ::isUlrReportingShape)
        }

        val spot = findClass(loader, SPOT_CLASSES)
        if (spot != null) {
            hookIntentMethod(spot, "Spot.onHandleIntent") { intent ->
                isAssignLocationAction(intent)
            }
            hookLocationMethods(spot, emptySet(), "Spot.fallback", allLocationMethods = true, shape = ::isControlledReportingShape)
        }

        for (clazz in findClasses(loader, MDM_CLASSES)) {
            hookLocationMethods(clazz, setOf("a", "b"), "MDM.primary", allLocationMethods = false)
            hookLocationMethods(clazz, setOf("a", "b"), "MDM.fallback", allLocationMethods = false) { method ->
                method.parameterTypes.count { it == Location::class.java } == 1
            }
        }
    }

    private fun installConsumerPresentationHooks() {
        val loc = Location::class.java

        hookGetter(loc, "getLatitude") { current -> configOrNull()?.latitude ?: current }
        hookGetter(loc, "getLongitude") { current -> configOrNull()?.longitude ?: current }
        hookGetter(loc, "getAccuracy") { current -> configOrNull()?.accuracy ?: current }
        hookGetter(loc, "getAltitude") { current -> configOrNull()?.altitude ?: current }

        forceFalse(loc, "isFromMockProvider")
        if (Build.VERSION.SDK_INT >= 31) {
            forceFalse(loc, "isMock")
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun isEnabled(): Boolean = try {
        prefs.getBoolean(Prefs.KEY_ENABLED, false)
    } catch (_: Throwable) { false }

    private fun isMdmEnabled(): Boolean = try {
        prefs.getBoolean(Prefs.KEY_MDM_ENABLED, false)
    } catch (t: Throwable) {
        module.log(Log.WARN, TAG, "MDM preference read failed; preserving real Location: $t")
        false
    }

    private fun configOrNull(): FakeConfig? {
        return try {
            if (!prefs.getBoolean(Prefs.KEY_ENABLED, false)) return null
            val lat = readDouble(Prefs.KEY_LAT, Prefs.DEFAULT_LAT) ?: return null
            val lng = readDouble(Prefs.KEY_LNG, Prefs.DEFAULT_LNG) ?: return null
            val alt = readDouble(Prefs.KEY_ALT, Prefs.DEFAULT_ALT) ?: return null
            val acc = readFloat(Prefs.KEY_ACC, Prefs.DEFAULT_ACC) ?: return null
            if (!lat.isFinite() || !lng.isFinite() || !alt.isFinite() || !acc.isFinite()) return null
            if (lat !in -90.0..90.0 || lng !in -180.0..180.0 || acc < 0f) return null
            FakeConfig(lat, lng, alt, acc)
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "config read failed; preserving real Location: $t")
            null
        }
    }

    private fun readDouble(key: String, fallback: Double): Double? {
        val value = prefs.getString(key, null) ?: return fallback
        return value.trim().toDoubleOrNull()
    }

    private fun readFloat(key: String, fallback: Float): Float? {
        val value = prefs.getString(key, null) ?: return fallback
        return value.trim().toFloatOrNull()
    }

    private fun fakeLocation(source: Location, config: FakeConfig): Location? {
        return try {
            val fake = Location(source.provider)
            fake.time = source.time
            fake.elapsedRealtimeNanos = source.elapsedRealtimeNanos
            fake.latitude = config.latitude
            fake.longitude = config.longitude
            fake.altitude = config.altitude
            fake.accuracy = config.accuracy
            if (source.hasSpeed()) fake.speed = source.speed
            if (source.hasBearing()) fake.bearing = source.bearing
            if (Build.VERSION.SDK_INT >= 26) {
                if (source.hasVerticalAccuracy()) fake.verticalAccuracyMeters = source.verticalAccuracyMeters
                if (source.hasSpeedAccuracy()) fake.speedAccuracyMetersPerSecond = source.speedAccuracyMetersPerSecond
                if (source.hasBearingAccuracy()) fake.bearingAccuracyDegrees = source.bearingAccuracyDegrees
            }
            source.extras?.let { fake.extras = Bundle(it) }
            fake
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "failed to copy Location; preserving original: $t")
            null
        }
    }

    private fun findClass(loader: ClassLoader, names: Array<String>): Class<*>? {
        for (name in names) {
            try { return Class.forName(name, false, loader) } catch (_: Throwable) { }
        }
        return null
    }

    private fun findClasses(loader: ClassLoader, names: Array<String>): List<Class<*>> =
        names.mapNotNull { name -> try { Class.forName(name, false, loader) } catch (_: Throwable) { null } }.distinct()

    private fun declaredAndPublicMethods(clazz: Class<*>): List<Method> =
        (clazz.declaredMethods.asList() + clazz.methods.asList()).distinctBy { it.toGenericString() }

    private fun isControlledReportingShape(method: Method): Boolean {
        val locationCount = method.parameterTypes.count { it == Location::class.java }
        if (locationCount != 1 || method.parameterTypes.size > 4) return false
        val name = method.name.lowercase()
        if (name.startsWith("get") || name.startsWith("has") || name.startsWith("is")) return false
        return method.returnType == Void.TYPE || name.contains("location") || name in setOf("a", "b", "c", "d", "e", "m")
    }

    private fun isUlrReportingShape(method: Method): Boolean {
        val parameters = method.parameterTypes
        if (parameters.count { it == Location::class.java } != 1) return false
        if (parameters.size == 2) return parameters[1] == Location::class.java || parameters[0] == Location::class.java
        if (parameters.size == 4) return parameters[2] == Location::class.java && parameters[3] == Boolean::class.javaPrimitiveType
        return false
    }

    private fun hookLocationMethods(
        clazz: Class<*>,
        names: Set<String>,
        label: String,
        allLocationMethods: Boolean,
        shape: (Method) -> Boolean = { true },
    ) {
        for (method in declaredAndPublicMethods(clazz)) {
            if (method.isSynthetic || method.parameterTypes.none { it == Location::class.java }) continue
            if (!allLocationMethods && method.name !in names) continue
            if (!shape(method)) continue
            hookMember(method, label)
        }
    }

    private fun hookMember(method: Method, label: String) {
        val key = method.declaringClass.name + "#" + method.toGenericString()
        if (!installedMethods.add(key)) return
        try {
            module.hook(method)
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    replaceLocationArgs(chain.args.toTypedArray(), label)?.let { newArgs ->
                        chain.proceed(newArgs)
                    } ?: chain.proceed()
                }
            module.log(Log.INFO, TAG, "installed $label: $key")
        } catch (t: Throwable) {
            installedMethods.remove(key)
            module.log(Log.WARN, TAG, "failed to install $label: $key: $t")
        }
    }

    private fun replaceLocationArgs(args: Array<Any?>, label: String): Array<Any?>? {
        try {
            if (label.startsWith("MDM.") && !isMdmEnabled()) return null
            val config = configOrNull() ?: return null
            if (label.startsWith("Spot.") && depth() <= 0) return null
            var mutated = false
            val out = args.copyOf()
            for (index in out.indices) {
                val source = out[index] as? Location ?: continue
                val fake = fakeLocation(source, config) ?: continue
                out[index] = fake
                mutated = true
                logHitOnce(label)
            }
            return if (mutated) out else null
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "semantic hook $label failed open: $t")
            return null
        }
    }

    private fun hookIntentMethod(clazz: Class<*>, label: String, relevant: (Any?) -> Boolean) {
        for (method in declaredAndPublicMethods(clazz)) {
            if (method.name != "onHandleIntent" || method.parameterTypes.size != 1) continue
            if (method.parameterTypes[0].name != "android.content.Intent") continue
            val key = method.declaringClass.name + "#" + method.toGenericString()
            if (!installedMethods.add(key)) continue
            try {
                module.hook(method)
                    .setPriority(XposedInterface.PRIORITY_DEFAULT)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val intentArg = chain.args.firstOrNull()
                        val isRelevant = try { relevant(intentArg) } catch (_: Throwable) { false }
                        if (!isRelevant) return@intercept chain.proceed()
                        setDepth(depth() + 1)
                        try {
                            transformIntentPayload(intentArg)
                            logHitOnce("$label.relevant-action")
                        } catch (t: Throwable) {
                            module.log(Log.WARN, TAG, "action hook $label failed open: $t")
                        }
                        try {
                            chain.proceed()
                        } finally {
                            setDepth((depth() - 1).coerceAtLeast(0))
                        }
                    }
                module.log(Log.INFO, TAG, "installed $label: $key")
            } catch (t: Throwable) {
                installedMethods.remove(key)
                module.log(Log.WARN, TAG, "failed to install $label: $key: $t")
            }
        }
    }

    private fun isPeriodicReportingAction(value: Any?): Boolean {
        val action = try { (value as? android.content.Intent)?.action ?: return false } catch (_: Throwable) { return false }
        return PERIODIC_ACTIONS.any { matchesAction(action, it) }
    }

    private fun isAssignLocationAction(value: Any?): Boolean {
        val action = try { (value as? android.content.Intent)?.action ?: return false } catch (_: Throwable) { return false }
        return action == "ASSIGN_LOCATION" || action.endsWith(".ASSIGN_LOCATION")
    }

    private fun matchesAction(action: String, fullAction: String): Boolean {
        val suffix = fullAction.substringAfterLast('.')
        return action == fullAction || action == suffix || action.endsWith(".$suffix")
    }

    @Suppress("DEPRECATION")
    private fun transformIntentPayload(value: Any?) {
        try {
            val intent = value as? android.content.Intent ?: return
            val config = configOrNull() ?: return
            val extras = intent.extras ?: return
            for (key in extras.keySet().toList()) {
                val extra = try { extras.get(key) } catch (_: Throwable) { null } ?: continue
                when {
                    extra is Location -> {
                        fakeLocation(extra, config)?.let { intent.putExtra(key, it) }
                    }
                    isLocationResult(extra) -> {
                        val sourceLocations = locationResultLocations(extra) ?: continue
                        val fakeLocations = sourceLocations.mapNotNull { fakeLocation(it, config) }
                        if (fakeLocations.size != sourceLocations.size) continue
                        val result = newLocationResult(extra.javaClass, fakeLocations) ?: continue
                        val parcelable = result as? android.os.Parcelable ?: continue
                        intent.putExtra(key, parcelable)
                    }
                    extra is ArrayList<*> && extra.all { it is Location } -> {
                        val sourceLocations = extra.filterIsInstance<Location>()
                        val fakeLocations = sourceLocations.mapNotNull { fakeLocation(it, config) }
                        if (fakeLocations.size == sourceLocations.size) {
                            val parcelables = ArrayList<android.os.Parcelable>(fakeLocations.size)
                            parcelables.addAll(fakeLocations)
                            intent.putParcelableArrayListExtra(key, parcelables)
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "Intent payload rewrite failed open: $t")
        }
    }

    private fun isLocationResult(value: Any): Boolean {
        val name = value.javaClass.name
        return name == "com.google.android.gms.location.LocationResult" || name == "android.location.LocationResult"
    }

    private fun locationResultLocations(value: Any): List<Location>? {
        fun asLocations(candidate: Any?): List<Location>? {
            if (candidate !is List<*> || !candidate.all { it is Location }) return null
            return candidate.filterIsInstance<Location>()
        }
        try { asLocations(callMethod(value, "getLocations"))?.let { return it } } catch (_: Throwable) { }
        try { asLocations(getObjectField(value, "b"))?.let { return it } } catch (_: Throwable) { }
        try { asLocations(getObjectField(value, "locations"))?.let { return it } } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "LocationResult read failed open: $t")
        }
        return null
    }

    private fun newLocationResult(clazz: Class<*>, locations: List<Location>): Any? {
        return try {
            val list = ArrayList<Location>(locations)
            val factory = (clazz.methods.asList() + clazz.declaredMethods.asList()).firstOrNull { method ->
                method.name == "create" && java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                    method.parameterTypes.size == 1 && List::class.java.isAssignableFrom(method.parameterTypes[0])
            }
            if (factory != null) {
                factory.isAccessible = true
                return factory.invoke(null, list)
            }
            val constructor = clazz.declaredConstructors.firstOrNull { c ->
                c.parameterTypes.size == 1 && List::class.java.isAssignableFrom(c.parameterTypes[0])
            } ?: return null
            constructor.isAccessible = true
            constructor.newInstance(list)
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "LocationResult construction failed open: $t")
            null
        }
    }

    private fun logHitOnce(label: String) {
        if (loggedHits.add(label)) module.log(Log.INFO, TAG, "first hit: $label")
    }

    // Reflection helpers replacing XposedHelpers
    private fun callMethod(obj: Any, name: String, vararg args: Any?): Any? {
        var c: Class<*>? = obj.javaClass
        while (c != null) {
            for (m in c.declaredMethods) if (m.name == name && m.parameterTypes.size == args.size) {
                m.isAccessible = true; return m.invoke(obj, *args)
            }
            for (m in c.methods) if (m.name == name && m.parameterTypes.size == args.size) {
                m.isAccessible = true; return m.invoke(obj, *args)
            }
            c = c.superclass
        }
        throw NoSuchMethodException(name)
    }

    private fun getObjectField(obj: Any, name: String): Any? {
        var c: Class<*>? = obj.javaClass
        while (c != null) {
            try { val f = c.getDeclaredField(name); f.isAccessible = true; return f.get(obj) } catch (_: Throwable) { }
            c = c.superclass
        }
        throw NoSuchFieldException(name)
    }

    private inline fun hookGetter(
        clazz: Class<*>,
        method: String,
        crossinline transform: (current: Any?) -> Any?,
    ) {
        try {
            val m = clazz.getMethod(method)
            module.hook(m)
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    try { transform(result) ?: result } catch (t: Throwable) {
                        module.log(Log.WARN, TAG, "getter $method failed open: $t")
                        result
                    }
                }
            module.log(Log.INFO, TAG, "installed consumer getter: ${clazz.name}#$method")
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "failed to hook $method: $t")
        }
    }

    private fun forceFalse(clazz: Class<*>, method: String) {
        try {
            val m = clazz.getMethod(method)
            module.hook(m)
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    if (isEnabled()) false else result
                }
            module.log(Log.INFO, TAG, "installed consumer mock-state hook: ${clazz.name}#$method")
        } catch (_: Throwable) { }
    }

    private const val TAG = "GoogleLoc"
    private const val GMS_PACKAGE = "com.google.android.gms"
    private val PERIODIC_ACTIONS = setOf(
        "com.google.android.gms.locationsharingreporter.service.reporting.periodic.ACTION_PERIODIC_LOCATION_UPDATE",
        "com.google.android.gms.locationsharingreporter.service.reporting.periodic.ACTION_NEW_START_REPORTING_REQUEST",
        "com.google.android.gms.locationsharingreporter.service.reporting.periodic.ACTION_GEOFENCE_TRIGGERED",
    )

    private val LSR_REPORTING_CLASSES = arrayOf("com.google.android.gms.locationsharingreporter.service.reporting.LocationReportingIntentOperation")
    private val PERIODIC_REPORTING_CLASSES = arrayOf("com.google.android.gms.locationsharingreporter.service.reporting.PeriodicLocationReportingIntentOperation")
    private val TRACKING_CLASSES = arrayOf(
        "com.google.android.gms.locationsharingreporter.service.LocationTrackingIntentOperation",
        "com.google.android.gms.locationsharingreporter.service.reporting.LocationTrackingIntentOperation",
    )
    private val ULR_CLASSES = arrayOf(
        "hbps",
        "com.google.android.gms.locationsharingreporter.service.hbps",
        "com.google.android.gms.locationsharingreporter.service.reporting.hbps",
    )
    private val SPOT_CLASSES = arrayOf("com.google.android.gms.findmydevice.spot.locationreporting.LocationAssigningIntentOperation")
    private val MDM_CLASSES = arrayOf(
        "com.google.android.gms.mdm.LocateChimeraService",
        "com.google.android.gms.mdm.GcmReceiverChimeraService",
        "com.google.android.gms.mdm.services.LocateChimeraService",
        "com.google.android.gms.mdm.services.GcmReceiverChimeraService",
        "com.google.android.gms.mdm.receivers.GcmReceiverChimeraService",
        "com.google.android.gms.locationsharingreporter.service.mdm.LocateChimeraService",
        "com.google.android.gms.locationsharingreporter.service.mdm.GcmReceiverChimeraService",
        "com.google.android.gms.locationsharingreporter.service.LocateChimeraService",
        "com.google.android.gms.locationsharingreporter.service.GcmReceiverChimeraService",
    )
}
