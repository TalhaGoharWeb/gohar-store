package com.gohar.store

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.view.GravityCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.gohar.store.databinding.ActivityMainBinding
import com.gohar.store.databinding.ItemAppBinding
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private enum class Tab { DISCOVER, UPDATES }

    private lateinit var binding: ActivityMainBinding
    private lateinit var drawerToggle: ActionBarDrawerToggle
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

    /** A download changed (state or throttled progress): refresh just
     * that row + the hero, immediately, instead of waiting for the
     * 2-second poll. Posted, so it never fires mid-layout. */
    private val downloadListener: (String) -> Unit = { appId ->
        refreshHandler.post {
            val position = visibleApps().indexOfFirst { it.id == appId }
            if (position >= 0) adapter.notifyItemChanged(position)
            updateFeatured()
            updateBadges()
        }
    }

    /** The featured hero can be dismissed (×); the choice lives in the
     * "ui" prefs so it survives rotation and restarts. */
    private fun isHeroDismissed(): Boolean =
        getSharedPreferences("ui", MODE_PRIVATE).getBoolean("hero_dismissed", false)

    private fun setHeroDismissed(dismissed: Boolean) {
        getSharedPreferences("ui", MODE_PRIVATE).edit()
            .putBoolean("hero_dismissed", dismissed).apply()
        updateDrawerMenu()
        refreshList()
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
        // The launch splash theme (set on this Activity in the manifest)
        // is swapped back to the real theme before anything inflates.
        setTheme(R.style.Theme_GoharStore)
        super.onCreate(savedInstanceState)
        // If the previous run crashed, the global handler left a report
        // (see GoharStoreApp). Checked BEFORE setContentView on purpose:
        // a crash during layout inflation would otherwise hide the
        // report forever, because onCreate would never reach the check.
        showCrashReportIfAny()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        // The toolbar has its own custom logo + name layout; without
        // this, AppCompat shows the Activity label ("Talha Gohar Apps")
        // as the toolbar title too, so the name appears twice.
        supportActionBar?.setDisplayShowTitleEnabled(false)

        // Hamburger + side drawer (v1.3.11): the toggle draws the
        // hamburger on the toolbar and opens the drawer; the old
        // three-dot overflow menu is gone.
        drawerToggle = ActionBarDrawerToggle(
            this, binding.drawerLayout, binding.toolbar,
            R.string.drawer_open, R.string.drawer_close,
        )
        binding.drawerLayout.addDrawerListener(drawerToggle)
        drawerToggle.syncState()
        binding.navView.setNavigationItemSelectedListener { item ->
            handleDrawerItem(item)
            true
        }
        binding.navView.getHeaderView(0)
            .findViewById<TextView>(R.id.navVersion)
            .text = getString(R.string.nav_version, BuildConfig.VERSION_NAME)
        updateDrawerMenu()

        // Start as a plain list; setupResponsive() swaps in a grid on
        // wide screens once the real width is known, after first layout.
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
        setupResponsive()

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
        AppDownloader.addListener(downloadListener)

        binding.featuredDismiss.setOnClickListener { setHeroDismissed(true) }

        // Back on the home screen asks first — this is the root
        // Activity, so there is nothing to go back to.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // An open drawer closes first; only then the exit
                // confirmation. (The drawer is on the start side.)
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    showExitConfirmation()
                }
            }
        })

        binding.bottomNav.setOnItemSelectedListener { item ->
            tab = if (item.itemId == R.id.nav_updates) Tab.UPDATES else Tab.DISCOVER
            refreshList()
            true
        }

        reload()
    }

    /** If the previous launch crashed, show the report the global
     * handler wrote (see GoharStoreApp) so it can be copied and sent
     * to us. "Copy report" keeps the file; "Dismiss" deletes it, so
     * the dialog only shows once per crash. Never throws. */
    private fun showCrashReportIfAny() {
        val file = java.io.File(filesDir, GoharStoreApp.CRASH_REPORT_FILE)
        if (!file.exists()) return
        val report = try {
            file.readText()
        } catch (_: Exception) {
            return
        }
        try {
            val instruction = TextView(this).apply {
                text = "Please copy this report and send it to us so the crash can be fixed."
                setPadding(48, 32, 48, 16)
            }
            val traceView = TextView(this).apply {
                text = report
                setTextIsSelectable(true)
                setPadding(48, 16, 48, 32)
            }
            val scroll = ScrollView(this).apply { addView(traceView) }
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(instruction)
                addView(scroll)
            }
            AlertDialog.Builder(this)
                .setTitle("The app crashed last time")
                .setView(content)
                .setPositiveButton("Copy report") { _, _ ->
                    try {
                        val clipboard =
                            getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText("crash report", report),
                        )
                        Toast.makeText(this, "Crash report copied", Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) {
                        Toast.makeText(this, "Could not copy the report", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Dismiss") { _, _ -> file.delete() }
                .show()
        } catch (_: Exception) {
            // Showing the report must never crash the app itself.
        }
    }

    /** Back on the home screen: ask first, only exit on confirm. */
    private fun showExitConfirmation() {
        AlertDialog.Builder(this)
            .setTitle(R.string.exit_title)
            .setMessage(R.string.exit_message)
            .setPositiveButton(R.string.exit_confirm) { _, _ -> finish() }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    /** Drawer item visibility, same rules the old overflow menu had:
     * the developer console is owner-only (it only shows while the
     * owner is signed in; everyone else just sees "Owner sign-in"),
     * and "Show featured app" only shows while the hero is dismissed. */
    private fun updateDrawerMenu() {
        val signedIn = AuthManager.isOwnerSignedIn(this)
        val menu = binding.navView.menu
        menu.findItem(R.id.developer_console)?.isVisible = signedIn
        menu.findItem(R.id.owner_sign_in)?.isVisible = !signedIn
        menu.findItem(R.id.owner_sign_out)?.isVisible = signedIn
        menu.findItem(R.id.show_featured)?.isVisible = isHeroDismissed()
    }

    /** One tap in the drawer = one action, then the drawer closes.
     * Same actions the overflow menu used to run. */
    private fun handleDrawerItem(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.show_featured -> setHeroDismissed(false)
            R.id.about_author -> startActivity(Intent(this, AboutActivity::class.java))
            R.id.developer_console -> {
                if (AuthManager.isOwnerSignedIn(this)) {
                    startActivity(Intent(this, AdminActivity::class.java))
                } else {
                    startActivity(Intent(this, AuthActivity::class.java))
                }
            }
            R.id.owner_sign_in -> startActivity(Intent(this, AuthActivity::class.java))
            R.id.owner_sign_out -> {
                AuthManager.signOut(this)
                Toast.makeText(this, R.string.signed_out, Toast.LENGTH_SHORT).show()
                updateDrawerMenu()
            }
            R.id.language_urdu -> AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ur"))
            R.id.language_english -> AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
        }
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    override fun onResume() {
        super.onResume()
        updateDrawerMenu()
        refreshList()
        refreshHandler.postDelayed(refreshRunnable, 2000)
    }

    override fun onPause() {
        super.onPause()
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    override fun onDestroy() {
        AppDownloader.removeListener(downloadListener)
        super.onDestroy()
    }

    /** Responsive layout: on screens wider than the content cap the
     * column is centred at @dimen/content_max_width, and the list
     * becomes a grid — one column per ~360dp of content width, so a
     * 360dp phone stays one column and a >=600dp screen gets two.
     * Runs after first layout (the parent width is 0 before that);
     * onCreate runs again on rotation, so it recomputes there too. */
    private fun setupResponsive() {
        binding.contentColumn.post {
            val parentWidth = (binding.contentColumn.parent as View).width
            if (parentWidth <= 0) return@post
            val maxPx = resources.getDimensionPixelSize(R.dimen.content_max_width)
            val contentWidthPx = if (parentWidth > maxPx) {
                val lp = binding.contentColumn.layoutParams as LinearLayout.LayoutParams
                lp.width = maxPx
                lp.gravity = Gravity.CENTER_HORIZONTAL
                binding.contentColumn.layoutParams = lp
                maxPx
            } else {
                parentWidth
            }
            val contentWidthDp = contentWidthPx / resources.displayMetrics.density
            val span = (contentWidthDp / 360f).roundToInt().coerceAtLeast(1)
            if (span > 1) binding.recycler.layoutManager = GridLayoutManager(this, span)
        }
    }

    private fun reload() {
        // First load only: show the real loading state while the list
        // is still empty. Pull-to-refresh with data keeps the list and
        // just spins the swipe indicator.
        if (catalog.apps.isEmpty()) binding.loadingState.visibility = View.VISIBLE
        binding.swipe.isRefreshing = true
        CatalogLoader.load(this) { loaded ->
            catalog = loaded
            binding.swipe.isRefreshing = false
            binding.loadingState.visibility = View.GONE
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
                // Nastaliq needs a taller chip in the Urdu locale;
                // the height comes from dimens (32dp / 40dp in ur).
                minimumHeight = resources.getDimensionPixelSize(R.dimen.chip_min_height)
                // A Chip created in code does NOT inherit the theme's
                // fontFamily — without this, Urdu labels render in the
                // default Latin font instead of Nastaleeq.
                applyUrduFontIfNeeded(this)
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
            if (isDiscover && query.isEmpty() && !isHeroDismissed()) View.VISIBLE else View.GONE
        binding.chipsScroll.visibility = if (isDiscover) View.VISIBLE else View.GONE
        val list = visibleApps()
        // The section title carries the real count from the catalogue —
        // no invented numbers, just how many apps match right now.
        binding.sectionTitle.text = when {
            tab == Tab.UPDATES -> getString(R.string.your_apps_count, list.size)
            category.isNotEmpty() && category != getString(R.string.all_apps) ->
                getString(R.string.category_count, category, list.size)
            query.isNotEmpty() -> getString(R.string.results_count, list.size)
            else -> getString(R.string.all_apps_count, list.size)
        }
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
        if (featured == null || tab != Tab.DISCOVER || query.isNotEmpty() || isHeroDismissed()) {
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
        if (state == AppState.DOWNLOADING || state == AppState.PAUSED) {
            val progress = StoreActions.downloadProgress(this, app)
            b.downloadProgress.visibility = View.VISIBLE
            if (progress >= 0) {
                b.downloadProgress.isIndeterminate = false
                b.downloadProgress.progress = progress
            } else {
                b.downloadProgress.isIndeterminate = true
            }
            b.downloadControls.visibility = View.VISIBLE
            b.downloadPauseButton.text = if (state == AppState.PAUSED) {
                getString(R.string.resume_download)
            } else {
                getString(R.string.pause_download)
            }
            b.downloadPauseButton.setOnClickListener {
                if (StoreActions.stateFor(this, app) == AppState.PAUSED) {
                    StoreActions.resumeDownload(this, app)
                } else {
                    StoreActions.pauseDownload(this, app)
                }
                refreshList()
            }
            b.downloadCancelButton.setOnClickListener {
                StoreActions.cancelDownload(this, app)
                refreshList()
            }
        } else {
            b.downloadProgress.visibility = View.GONE
            b.downloadControls.visibility = View.GONE
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
