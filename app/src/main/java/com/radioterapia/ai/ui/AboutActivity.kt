package com.radioterapia.ai.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.radioterapia.ai.BuildConfig
import com.radioterapia.ai.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AboutActivity : com.radioterapia.ai.BaseActivity() {

    override fun tituloPadrao(): String = getString(R.string.about)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)
        supportActionBar?.title = getString(R.string.about)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val versao = BuildConfig.VERSION_NAME
        val build = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

        @Suppress("HardwareIds")
        val deviceId = (Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            ?: "unknown").take(12)

        findViewById<TextView>(R.id.txtAboutVersion).text = getString(R.string.about_version, versao)
        findViewById<TextView>(R.id.txtAboutBuild).text = getString(R.string.about_build, build)
        findViewById<TextView>(R.id.txtAboutDeviceId).text = getString(R.string.about_device_id, deviceId)

        findViewById<Button>(R.id.btnOpenWebsite).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(com.radioterapia.ai.branding.Marca.SITE)))
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.hc_no_browser), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
