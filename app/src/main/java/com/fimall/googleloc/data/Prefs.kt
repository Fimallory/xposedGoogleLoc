package com.fimall.googleloc.data

/**
 * Shared constants for the config store.
 *
 * Config is persisted by the framework as RemotePreferences under [FILE], so
 * the same constants are usable from the UI process (read/write through
 * `XposedService.getRemotePreferences`) and from every hooked process
 * (read-only through `XposedModule.getRemotePreferences`).
 *
 * Coordinates are stored as WGS-84 decimal degrees, serialized as strings to
 * preserve full double precision across [android.content.SharedPreferences]
 * (a Float would lose sub-meter precision).
 */
object Prefs {
    /** RemotePreferences group name. */
    const val FILE = "googleloc_config"

    const val KEY_ENABLED = "enabled"
    const val KEY_MDM_ENABLED = "mdm_enabled"
    const val KEY_LAT = "lat"      // WGS-84 latitude, decimal degrees, as String
    const val KEY_LNG = "lng"      // WGS-84 longitude, decimal degrees, as String
    const val KEY_ALT = "alt"      // altitude in meters (WGS-84 ellipsoid), as String
    const val KEY_ACC = "acc"      // horizontal accuracy in meters, as String

    const val DEFAULT_LAT = 39.9042    // Beijing (Tiananmen)
    const val DEFAULT_LNG = 116.4074
    const val DEFAULT_ALT = 50.0
    const val DEFAULT_ACC = 8.0f
}
