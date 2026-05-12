package com.autoclicker.basic

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val statusText = findViewById<TextView>(R.id.statusText)
        val openAccessibilityBtn = findViewById<Button>(R.id.openAccessibilityBtn)
        val checkBtn = findViewById<Button>(R.id.checkBtn)

        openAccessibilityBtn.setOnClickListener {
            startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        checkBtn.setOnClickListener {
            val enabled = AutoClickAccessibilityService.isRunning
            statusText.text = if (enabled) {
                getString(R.string.accessibility_enabled_hint)
            } else {
                getString(R.string.accessibility_disabled_hint)
            }
        }
    }
}
