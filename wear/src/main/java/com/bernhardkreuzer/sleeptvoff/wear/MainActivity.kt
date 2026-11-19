package com.bernhardkreuzer.sleeptvoff.wear

import android.Manifest
import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * Minimal-Oberfläche auf der Watch: Berechtigung holen, Status zeigen und
 * die Kette Watch -> Handy -> Fernseher testen, ohne dafür einschlafen zu müssen.
 */
class MainActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()
    private lateinit var txtStatus: TextView
    private lateinit var txtLast: TextView
    private lateinit var btnPermission: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        txtStatus = findViewById(R.id.txtStatus)
        txtLast = findViewById(R.id.txtLast)
        btnPermission = findViewById(R.id.btnPermission)

        btnPermission.setOnClickListener { askPermission() }
        findViewById<Button>(R.id.btnTestAsleep).setOnClickListener {
            sendTest(PhoneSync.STATE_ASLEEP, "Test: eingeschlafen gesendet")
        }
        findViewById<Button>(R.id.btnTestAwake).setOnClickListener {
            sendTest(PhoneSync.STATE_AWAKE, "Test: wach gesendet")
        }

        if (!PassiveRegistration.hasPermission(this)) askPermission()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        register()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
        register()
    }

    private fun askPermission() {
        requestPermissions(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), REQUEST_PERMISSION)
    }

    private fun register() {
        if (!PassiveRegistration.hasPermission(this)) return
        val app = applicationContext
        io.execute {
            val status = try {
                PassiveRegistration.registerBlocking(app)
            } catch (e: Exception) {
                "Fehler: ${e.message ?: e.javaClass.simpleName}"
            }
            WearStatus.setRegistration(app, status)
            runOnUiThread { refresh() }
        }
    }

    private fun sendTest(state: String, okText: String) {
        PhoneSync.send(this, state, System.currentTimeMillis(), test = true)
            .addOnSuccessListener { toast(okText) }
            .addOnFailureListener { e -> toast("Senden fehlgeschlagen: ${e.message}") }
    }

    private fun refresh() {
        val granted = PassiveRegistration.hasPermission(this)
        btnPermission.visibility = if (granted) View.GONE else View.VISIBLE
        txtStatus.text = if (granted) WearStatus.registration(this) else "Berechtigung fehlt"
        txtLast.text = "Zuletzt: ${WearStatus.lastEvent(this)}"
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val REQUEST_PERMISSION = 1
    }
}
