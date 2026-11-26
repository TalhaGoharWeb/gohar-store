package com.gohar.store

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.gohar.store.databinding.ActivityDetailBinding

class DetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_APP_ID = "app_id"
    }

    private lateinit var binding: ActivityDetailBinding
    private var app: StoreApp? = null
    private val pollHandler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            refreshState()
            pollHandler.postDelayed(this, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val appId = intent.getStringExtra(EXTRA_APP_ID) ?: ""
        CatalogLoader.load(this) { catalog ->
            app = catalog.apps.firstOrNull { it.id == appId }
            val found = app
            if (found == null) {
                finish()
                return@load
            }
            bind(found)
            refreshState()
        }

        binding.actionButton.setOnClickListener {
            app?.let { StoreActions.primaryAction(this, it) }
            refreshState()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshState()
        pollHandler.postDelayed(pollRunnable, 1500)
    }

    override fun onPause() {
        super.onPause()
        pollHandler.removeCallbacks(pollRunnable)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun bind(app: StoreApp) {
        binding.detailHeader.background = MainActivity.gradientDrawable(app, 0f)
        binding.detailIcon.text = app.iconLetter
        binding.detailIcon.background =
            resources.getDrawable(R.drawable.chip_translucent, theme)
        binding.detailName.text = app.name
        binding.detailTagline.text = app.displayTagline()
        binding.detailDeveloper.text = "By ${app.developer}"
        binding.chipVersion.text = "v${app.versionName}"
        binding.chipSize.text = app.sizeLabel().ifEmpty { getString(R.string.size_unknown) }
        binding.chipCategory.text = app.displayCategory()
        binding.detailDescription.text = app.displayDescription()
        binding.detailMeta.text = buildString {
            append("Version ${app.versionName} (code ${app.versionCode})")
            if (app.sizeLabel().isNotEmpty()) append("\nSize ${app.sizeLabel()}")
            append("\nDeveloper ${app.developer}")
            append("\nCategory ${app.category}")
            append("\nPackage ${app.packageName}")
            append("\nNeeds Android ${app.minSdk}+")
        }
        if (app.displayWhatsNew().isEmpty()) {
            binding.whatsNewCard.visibility = View.GONE
        } else {
            binding.whatsNewCard.visibility = View.VISIBLE
            binding.whatsNewList.removeAllViews()
            for (line in app.displayWhatsNew()) {
                val tv = TextView(this).apply {
                    text = "•  $line"
                    textSize = 15f
                    setPadding(0, 6, 0, 6)
                }
                binding.whatsNewList.addView(tv)
            }
        }
        val comingSoon = app.displayComingSoon()
        if (comingSoon != null) {
            binding.comingSoonCard.visibility = View.VISIBLE
            binding.comingSoonNote.text = comingSoon
        } else {
            binding.comingSoonCard.visibility = View.GONE
        }
    }

    private fun refreshState() {
        val app = app ?: return
        val state = StoreActions.stateFor(this, app)
        binding.actionButton.text = StoreActions.actionLabel(this, app)
        binding.actionButton.isEnabled =
            state != AppState.DOWNLOADING && state != AppState.COMING_SOON
        val installed = StoreActions.installedVersionCode(this, app.packageName)
        binding.installedNote.visibility =
            if (installed != null) View.VISIBLE else View.GONE
        if (installed != null) {
            binding.installedNote.text = if (installed < app.versionCode) {
                getString(R.string.installed_older)
            } else {
                getString(R.string.installed_uptodate)
            }
        }
        if (state == AppState.DOWNLOADING) {
            binding.downloadProgress.visibility = View.VISIBLE
            val progress = StoreActions.downloadProgress(this, app)
            if (progress >= 0) {
                binding.downloadProgress.progress = progress
                binding.downloadNote.visibility = View.VISIBLE
                binding.downloadNote.text = getString(R.string.downloading_progress, progress)
            }
        } else {
            binding.downloadProgress.visibility = View.GONE
            binding.downloadNote.visibility = View.GONE
        }
    }
}
