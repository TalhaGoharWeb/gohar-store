package com.gohar.store

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.gohar.store.databinding.ActivityMainBinding
import com.gohar.store.databinding.ItemAppBinding

class MainActivity : AppCompatActivity() {

    private enum class Tab { DISCOVER, UPDATES }

    private lateinit var binding: ActivityMainBinding
    private var catalog: Catalog = Catalog(emptyList(), "", false)
    private var query: String = ""
    private var category: String = ""
    private var tab: Tab = Tab.DISCOVER
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            adapter.notifyDataSetChanged()
            updateFeatured()
            updateBadges()
            refreshHandler.postDelayed(this, 2000)
        }
    }

    private class AppViewHolder(val binding: ItemAppBinding) :
        RecyclerView.ViewHolder(binding.root)

    private val adapter = object : RecyclerView.Adapter<AppViewHolder>() {
        override fun getItemCount(): Int = visibleApps().size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
            val b = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return AppViewHolder(b)
        }

        override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
            bindRow(holder.binding, visibleApps()[position])
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                query = s?.toString()?.trim() ?: ""
                refreshList()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.swipe.setOnRefreshListener { reload() }
        binding.swipe.setColorSchemeColors(Color.parseColor("#0E9594"))
        binding.refreshButton.setOnClickListener { reload() }

        binding.bottomNav.setOnItemSelectedListener { item ->
            tab = if (item.itemId == R.id.nav_updates) Tab.UPDATES else Tab.DISCOVER
            refreshList()
            true
        }

        reload()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.developer_console -> {
                startActivity(Intent(this, AdminActivity::class.java))
                return true
            }
            R.id.language_urdu -> {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ur"))
                return true
            }
            R.id.language_english -> {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onResume() {
        super.onResume()
        refreshList()
        refreshHandler.postDelayed(refreshRunnable, 2000)
    }

    override fun onPause() {
        super.onPause()
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    private fun reload() {
        binding.swipe.isRefreshing = true
        CatalogLoader.load(this) { loaded ->
            catalog = loaded
            binding.swipe.isRefreshing = false
            binding.sourceNote.text = if (loaded.fromRemote) {
                getString(R.string.online_catalogue, loaded.updated)
            } else {
                getString(R.string.offline_catalogue)
            }
            buildChips()
            refreshList()
        }
    }

    private fun buildChips() {
        val allLabel = getString(R.string.all_apps)
        val categories = listOf(allLabel) + catalog.apps.map { it.displayCategory() }.distinct()
        binding.chipGroup.removeAllViews()
        for (name in categories) {
            val chip = Chip(this).apply {
                text = name
                isCheckable = true
                isChecked = name == category
                setOnClickListener {
                    category = name
                    refreshList()
                }
            }
            binding.chipGroup.addView(chip)
        }
        if (category !in categories) category = allLabel
    }

    private fun visibleApps(): List<StoreApp> {
        var list = catalog.apps
        if (tab == Tab.UPDATES) {
            list = list.filter { StoreActions.installedVersionCode(this, it.packageName) != null }
                .sortedByDescending { StoreActions.stateFor(this, it) == AppState.UPDATE_AVAILABLE }
        }
        if (category.isNotEmpty() && category != getString(R.string.all_apps)) {
            list = list.filter { it.displayCategory() == category }
        }
        if (query.isNotEmpty()) {
            list = list.filter {
                it.displayName().contains(query, ignoreCase = true) ||
                    it.name.contains(query, ignoreCase = true) ||
                    it.displayTagline().contains(query, ignoreCase = true) ||
                    it.displayCategory().contains(query, ignoreCase = true)
            }
        }
        return list
    }

    private fun refreshList() {
        val isDiscover = tab == Tab.DISCOVER
        binding.featuredCard.visibility =
            if (isDiscover && query.isEmpty()) View.VISIBLE else View.GONE
        binding.chipsScroll.visibility = if (isDiscover) View.VISIBLE else View.GONE
        binding.sectionTitle.text = when {
            tab == Tab.UPDATES -> getString(R.string.your_apps)
            category.isNotEmpty() && category != getString(R.string.all_apps) -> category
            query.isNotEmpty() -> getString(R.string.results)
            else -> getString(R.string.all_apps)
        }
        val list = visibleApps()
        binding.emptyState.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        binding.emptyState.text = when {
            tab == Tab.UPDATES -> getString(R.string.no_apps_installed)
            else -> getString(R.string.no_apps_search)
        }
        adapter.notifyDataSetChanged()
        updateFeatured()
        updateBadges()
    }

    private fun updateBadges() {
        val count = catalog.apps.count {
            StoreActions.stateFor(this, it) == AppState.UPDATE_AVAILABLE
        }
        val badge = binding.bottomNav.getOrCreateBadge(R.id.nav_updates)
        badge.isVisible = count > 0
        badge.number = count
    }

    private fun updateFeatured() {
        val featured = catalog.apps.firstOrNull { it.featured }
        if (featured == null || tab != Tab.DISCOVER || query.isNotEmpty()) {
            binding.featuredCard.visibility = View.GONE
            return
        }
        binding.featuredCard.visibility = View.VISIBLE
        binding.featuredBackground.background = gradientDrawable(featured, 24f)
        binding.featuredIcon.text = featured.iconLetter
        binding.featuredIcon.background =
            resources.getDrawable(R.drawable.chip_translucent, theme)
        binding.featuredIcon.visibility = View.VISIBLE
        binding.featuredIconImage.visibility = View.GONE
        binding.featuredIconImage.contentDescription = featured.name
        ImageLoader.load(featured.iconUrl, binding.featuredIconImage, binding.featuredIcon)
        binding.featuredName.text = featured.displayName()
        binding.featuredTagline.text = featured.displayTagline()
        binding.featuredMeta.text =
            "v${featured.versionName} · ${featured.sizeLabel()} · ${featured.displayCategory()}"
        binding.featuredButton.text = StoreActions.actionLabel(this, featured)
        binding.featuredButton.isEnabled =
            StoreActions.stateFor(this, featured) != AppState.DOWNLOADING
        binding.featuredCard.setOnClickListener { openDetail(featured) }
        binding.featuredButton.setOnClickListener {
            StoreActions.primaryAction(this, featured)
            refreshList()
        }
        binding.featuredDetailsButton.setOnClickListener { openDetail(featured) }
    }

    private fun bindRow(b: ItemAppBinding, app: StoreApp) {
        b.appIcon.text = app.iconLetter
        b.appIcon.background = gradientDrawable(app, 18f)
        b.appIcon.visibility = View.VISIBLE
        b.appIconImage.visibility = View.GONE
        b.appIconImage.contentDescription = app.name
        ImageLoader.load(app.iconUrl, b.appIconImage, b.appIcon)
        b.appName.text = app.displayName()
        b.appDeveloper.text = "${app.developer} · ${app.displayCategory()}"
        val installed = StoreActions.installedVersionCode(this, app.packageName)
        val state = StoreActions.stateFor(this, app)
        b.appMeta.text = buildString {
            append("v${app.versionName}")
            if (app.sizeLabel().isNotEmpty()) append("  ·  ${app.sizeLabel()}")
            if (installed != null) append("  ·  Installed")
        }
        b.appAction.text = StoreActions.actionLabel(this, app)
        b.appAction.isEnabled = state != AppState.DOWNLOADING && state != AppState.COMING_SOON
        b.updateBadge.visibility =
            if (state == AppState.UPDATE_AVAILABLE) View.VISIBLE else View.GONE
        if (state == AppState.DOWNLOADING) {
            val progress = StoreActions.downloadProgress(this, app)
            b.downloadProgress.visibility = View.VISIBLE
            if (progress >= 0) {
                b.downloadProgress.isIndeterminate = false
                b.downloadProgress.progress = progress
            } else {
                b.downloadProgress.isIndeterminate = true
            }
        } else {
            b.downloadProgress.visibility = View.GONE
        }
        b.root.setOnClickListener { openDetail(app) }
        b.appAction.setOnClickListener {
            StoreActions.primaryAction(this, app)
            refreshList()
        }
    }

    private fun openDetail(app: StoreApp) {
        startActivity(Intent(this, DetailActivity::class.java).apply {
            putExtra(DetailActivity.EXTRA_APP_ID, app.id)
        })
    }

    companion object {
        fun parseColor(hex: String, fallback: String): Int {
            return try {
                Color.parseColor(hex)
            } catch (_: Exception) {
                Color.parseColor(fallback)
            }
        }

        /** Rounded two-colour gradient — the store's app-icon look. */
        fun gradientDrawable(app: StoreApp, cornerDp: Float): GradientDrawable {
            val radius = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, cornerDp,
                android.content.res.Resources.getSystem().displayMetrics,
            )
            return GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(parseColor(app.iconColor, "#0E9594"), parseColor(app.iconColor2, "#0B2545")),
            ).apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radius
            }
        }

        /** Backwards-compatible helper kept for the detail screen. */
        fun circleDrawable(hexColor: String): GradientDrawable {
            return GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(parseColor(hexColor, "#0E9594"))
            }
        }
    }
}
