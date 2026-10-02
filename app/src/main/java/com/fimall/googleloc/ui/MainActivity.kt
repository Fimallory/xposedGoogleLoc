package com.fimall.googleloc.ui

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.fimall.googleloc.App
import com.fimall.googleloc.R
import com.fimall.googleloc.data.Prefs
import com.google.android.material.materialswitch.MaterialSwitch
import io.github.libxposed.service.XposedService

class MainActivity : AppCompatActivity(), App.ServiceStateListener {

    private lateinit var switchEnabled: MaterialSwitch
    private lateinit var switchMdmEnabled: MaterialSwitch
    private lateinit var editLat: EditText
    private lateinit var editLng: EditText
    private lateinit var editAlt: EditText
    private lateinit var editAcc: EditText
    private lateinit var status: TextView
    private lateinit var btnSave: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        switchEnabled = findViewById(R.id.switch_enabled)
        switchMdmEnabled = findViewById(R.id.switch_mdm_enabled)
        editLat = findViewById(R.id.edit_lat)
        editLng = findViewById(R.id.edit_lng)
        editAlt = findViewById(R.id.edit_alt)
        editAcc = findViewById(R.id.edit_acc)
        status = findViewById(R.id.text_status)
        btnSave = findViewById(R.id.btn_save)

        btnSave.setOnClickListener { save() }
    }

    override fun onStart() {
        super.onStart()
        App.addServiceStateListener(this, true)
    }

    override fun onStop() {
        super.onStop()
        App.removeServiceStateListener(this)
    }

    override fun onServiceStateChanged(service: XposedService?) {
        runOnUiThread {
            if (service == null) {
                btnSave.isEnabled = false
                status.text = getString(R.string.service_not_ready)
                return@runOnUiThread
            }
            btnSave.isEnabled = true
            load(service)
        }
    }

    private fun load(service: XposedService) {
        try {
            val prefs = service.getRemotePreferences(Prefs.FILE)
            switchEnabled.isChecked = prefs.getBoolean(Prefs.KEY_ENABLED, false)
            switchMdmEnabled.isChecked = prefs.getBoolean(Prefs.KEY_MDM_ENABLED, false)
            editLat.setText(prefs.getString(Prefs.KEY_LAT, Prefs.DEFAULT_LAT.toString()))
            editLng.setText(prefs.getString(Prefs.KEY_LNG, Prefs.DEFAULT_LNG.toString()))
            editAlt.setText(prefs.getString(Prefs.KEY_ALT, Prefs.DEFAULT_ALT.toString()))
            editAcc.setText(prefs.getString(Prefs.KEY_ACC, Prefs.DEFAULT_ACC.toString()))
            updateStatusText()
        } catch (t: Throwable) {
            Toast.makeText(this, getString(R.string.read_failed, t.toString()), Toast.LENGTH_SHORT).show()
        }
    }

    private fun save() {
        val lat = editLat.text.toString().trim().toDoubleOrNull()
        val lng = editLng.text.toString().trim().toDoubleOrNull()
        if (lat == null || lat < -90.0 || lat > 90.0) { toast(getString(R.string.err_lat)); return }
        if (lng == null || lng < -180.0 || lng > 180.0) { toast(getString(R.string.err_lng)); return }
        val alt = editAlt.text.toString().trim().toDoubleOrNull() ?: Prefs.DEFAULT_ALT
        val acc = editAcc.text.toString().trim().toFloatOrNull() ?: Prefs.DEFAULT_ACC

        val service = App.serviceOrNull()
        if (service == null) {
            toast(getString(R.string.service_not_ready))
            return
        }
        try {
            val prefs = service.getRemotePreferences(Prefs.FILE)
            prefs.edit()
                .putBoolean(Prefs.KEY_ENABLED, switchEnabled.isChecked)
                .putBoolean(Prefs.KEY_MDM_ENABLED, switchMdmEnabled.isChecked)
                .putString(Prefs.KEY_LAT, lat.toString())
                .putString(Prefs.KEY_LNG, lng.toString())
                .putString(Prefs.KEY_ALT, alt.toString())
                .putString(Prefs.KEY_ACC, acc.toString())
                .apply()
            updateStatusText()
            toast(getString(R.string.saved))
        } catch (t: Throwable) {
            toast(getString(R.string.save_failed) + ": $t")
        }
    }

    private fun updateStatusText() {
        val lat = editLat.text.toString().trim().toDoubleOrNull() ?: Prefs.DEFAULT_LAT
        val lng = editLng.text.toString().trim().toDoubleOrNull() ?: Prefs.DEFAULT_LNG
        status.text = getString(
            if (switchEnabled.isChecked) R.string.status_on else R.string.status_off,
            lat, lng
        )
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
