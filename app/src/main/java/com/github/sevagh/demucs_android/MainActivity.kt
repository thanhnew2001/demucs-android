package com.github.sevagh.demucs_android

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.commit

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // force light mode
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Load the DemixerFragment into the activity
        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                replace(R.id.fragment_container, DemixerFragment())
            }
        }
    }
}
