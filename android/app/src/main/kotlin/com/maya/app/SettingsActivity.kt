package com.maya.app

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = TextView(this).apply {
            text = "Maya"
            textSize = 24f
            setPadding(48, 48, 48, 48)
        }
        setContentView(view)
    }
}
