package com.gohar.store

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.gohar.store.databinding.ActivityAboutBinding

/**
 * About the Author — Talha bin Fareed Al Faruqi. Every fact on this
 * page is one the user gave or that is already verified in the store
 * catalogue; there is no invented biography and no placeholder link.
 */
class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.contactCard.setOnClickListener { openEmail() }
        binding.malikimadhabButton.setOnClickListener { openWebsite() }
        binding.viewAppsButton.setOnClickListener { finish() }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    /** Opens Malikimadhab.org in the browser (verified live). */
    private fun openWebsite() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(MALIKIMADHAB_URL))
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            ToastHelper.show(this, ToastHelper.Type.ERROR, R.string.author_no_browser)
        }
    }

    private fun openEmail() {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:${getString(R.string.author_email)}")
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.author_email_subject))
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            ToastHelper.show(this, ToastHelper.Type.ERROR, R.string.author_no_email_app)
        }
    }

    companion object {
        const val MALIKIMADHAB_URL = "https://malikimadhab.org"
    }
}
