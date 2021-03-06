package com.example;

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.Switch

class LauncherActivity : Activity(){

    override fun onCreate(savedInstanceState: Bundle?) {
        setContentView(R.layout.launcher)
        super.onCreate(savedInstanceState)
        val documentsProvider = DocumentsProvider.instance

        findViewById<Button>(R.id.reset_button).setOnClickListener {
            documentsProvider.filesService.reset()
        }

        findViewById<Button>(R.id.generate_content).setOnClickListener {
            documentsProvider.filesService.generateContent()
        }

        val enableHacksSwitch = findViewById<Switch>(R.id.enable_hacks_switch)
        enableHacksSwitch.isChecked = documentsProvider.hacksEnabled
        enableHacksSwitch.setOnCheckedChangeListener { _, state ->
            documentsProvider.hacksEnabled = state
        }

        val authenticateSwitch = findViewById<Switch>(R.id.authenticate_switch)
        authenticateSwitch.isChecked = documentsProvider.authenticated
        authenticateSwitch.setOnCheckedChangeListener { _, state ->
            documentsProvider.authenticated = state
        }
    }

}
