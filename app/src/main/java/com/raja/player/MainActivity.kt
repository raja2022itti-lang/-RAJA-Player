package com.raja.player

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val input = findViewById<EditText>(R.id.urlInput)

        findViewById<Button>(R.id.playButton).setOnClickListener {
            val uri = Uri.parse(input.text.toString().trim())
            val valid = (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrEmpty()
            if (!valid) {
                Toast.makeText(this, R.string.invalid_link, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            startActivity(Intent(this, PlayerActivity::class.java).setData(uri))
        }

        findViewById<Button>(R.id.langButton).setOnClickListener {
            val isArabic = resources.configuration.locales[0].language == "ar"
            AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.forLanguageTags(if (isArabic) "en" else "ar")
            )
        }
    }
}
