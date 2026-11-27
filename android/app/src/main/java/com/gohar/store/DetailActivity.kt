package com.gohar.store

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.gohar.store.databinding.ActivityDetailBinding
import com.gohar.store.databinding.ItemScreenshotBinding

class DetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_APP_ID = "app_id"
    }

    private lateinit var binding: ActivityDetailBinding
    private var app: StoreApp? = null
    private var screenshots: List<String> = emptyList()
    private val pollHandler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            refreshState()
            pollHandler.postDelayed(this, 1500)
        }
    }

    /** A download changed: refresh immediately instead of waiting for
     * the 1.5-second poll. */
    private val downloadListener: (String) -> Unit = { appId ->
        if (app?.id == appId) pollHandler.post { refreshState() }
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
        AppDownloader.addListener(downloadListener)

        binding.downloadPauseButton.setOnClickListener {
            app?.let {
                if (StoreActions.stateFor(this, it) == AppState.PAUSED) {
                    StoreActions.resumeDownload(this, it)
                } else {
                    StoreActions.pauseDownload(this, it)
                }
                refreshState()
            }
        }
        binding.downloadCancelButton.setOnClickListener {
            app?.let {
                StoreActions.cancelDownload(this, it)
                refreshState()
            }
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

    override fun onDestroy() {
        AppDownloader.removeListener(downloadListener)
        super.onDestroy()
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
        binding.detailIcon.visibility = View.VISIBLE
        binding.detailIconImage.visibility = View.GONE
        binding.detailIconImage.contentDescription = app.name
        ImageLoader.load(app.iconUrl, binding.detailIconImage, binding.detailIcon)
        binding.detailName.text = app.displayName()
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
        bindScreenshots(app)
    }

    /** Play Store-style screenshot strip; hidden when the app has none. */
    private fun bindScreenshots(app: StoreApp) {
        screenshots = app.screenshots
        if (screenshots.isEmpty()) {
            binding.screenshotsSection.visibility = View.GONE
            return
        }
        binding.screenshotsSection.visibility = View.VISIBLE
        binding.screenshotsTitle.text =
            "${getString(R.string.screenshots)} (${screenshots.size})"
        binding.screenshotList.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.screenshotList.adapter = ScreenshotAdapter()
    }

    private inner class ScreenshotAdapter :
        RecyclerView.Adapter<ScreenshotAdapter.Holder>() {

        inner class Holder(val binding: ItemScreenshotBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun getItemCount(): Int = screenshots.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val b = ItemScreenshotBinding.inflate(
                LayoutInflater.from(parent.context), parent, false,
            )
            return Holder(b)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val url = screenshots[position]
            holder.binding.screenshotImage.visibility = View.GONE
            ImageLoader.load(url, holder.binding.screenshotImage)
            holder.binding.root.setOnClickListener {
                app?.let {
                    startActivity(
                        Intent(this@DetailActivity, ScreenshotViewerActivity::class.java).apply {
                            putExtra(ScreenshotViewerActivity.EXTRA_APP_ID, it.id)
                            putExtra(ScreenshotViewerActivity.EXTRA_INDEX, position)
                        },
                    )
                }
            }
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
        val downloadState = AppDownloader.state(this, app.id)
        if (state == AppState.DOWNLOADING || state == AppState.PAUSED) {
            binding.downloadProgress.visibility = View.VISIBLE
            val progress = StoreActions.downloadProgress(this, app)
            if (progress >= 0) {
                binding.downloadProgress.isIndeterminate = false
                binding.downloadProgress.progress = progress
            } else {
                binding.downloadProgress.isIndeterminate = true
            }
            binding.downloadNote.visibility = View.VISIBLE
            binding.downloadNote.text = if (state == AppState.PAUSED) {
                if (progress >= 0) getString(R.string.download_paused, progress)
                else getString(R.string.download_paused_no_progress)
            } else {
                if (progress >= 0) getString(R.string.downloading_progress, progress)
                else getString(R.string.downloading)
            }
            binding.downloadControls.visibility = View.VISIBLE
            binding.downloadPauseButton.text = if (state == AppState.PAUSED) {
                getString(R.string.resume_download)
            } else {
                getString(R.string.pause_download)
            }
        } else {
            binding.downloadProgress.visibility = View.GONE
            binding.downloadControls.visibility = View.GONE
            if (downloadState == DownloadState.FAILED) {
                binding.downloadNote.visibility = View.VISIBLE
                binding.downloadNote.text = getString(R.string.download_failed)
            } else {
                binding.downloadNote.visibility = View.GONE
            }
        }
    }
}
