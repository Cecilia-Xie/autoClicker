package com.autoclicker.basic

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val statusText = findViewById<TextView>(R.id.statusText)
        val startBtn = findViewById<Button>(R.id.startBtn)
        val stopBtn = findViewById<Button>(R.id.stopBtn)

        startBtn.setOnClickListener {
            statusText.text = getString(R.string.status_running)
        }

        stopBtn.setOnClickListener {
            statusText.text = getString(R.string.status_stopped)
        }
    }
}
