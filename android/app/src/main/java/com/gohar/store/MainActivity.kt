package com.gohar.store

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import androidx.activity.OnBackPressedCallback
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
    private var catalog: Catalog = Catalog(emptyList(), "", false)
    private var query: String = ""
    private var category: String = ""
    private var tab: Tab = Tab.DISCOVER
    /** Skeleton pulse animator (C-52): created when the first-load
     * skeleton shows, cancelled when it hides / on destroy. */
    private var skeletonAnimator: ObjectAnimator? = null
    /** Hidden owner entry (owner decision 28 Nov ~03:17 PKT): taps
     * on the drawer-header version, with the time of the last tap.
     * The count resets if >2 s passes between taps. */
    private var versionTapCount = 0
    private var lastVersionTapTime = 0L
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

        // Hamburger + side drawer: the app bar's own 40dp hamburger
        // button opens the drawer (v1.3.13 — the stock Toolbar
        // navigation button is 56dp wide, too wide for the full
        // uncropped banner to share the row with the title).
        binding.drawerButton.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }
        binding.navView.setNavigationItemSelectedListener { item ->
            handleDrawerItem(item)
            true
        }
        // Hidden owner entry (owner decision 28 Nov ~03:17 PKT):
        // the version label stays visible and unstyled; 5 taps on
        // it within ~2 s of each other open the owner sign-in (or
        // the console when already signed in). Silent until it
        // triggers — no toast, no feedback.
        val navVersion = binding.navView.getHeaderView(0)
            .findViewById<TextView>(R.id.navVersion)
        navVersion.text = getString(R.string.nav_version, BuildConfig.VERSION_NAME)
        navVersion.setOnClickListener { onVersionTap() }
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
        // Pull-to-refresh spinner in the theme secondary colour
        // (C-40): Turquoise in dark, theme secondary in light.
        val spinnerColor = TypedValue()
        if (theme.resolveAttribute(
                com.google.android.material.R.attr.colorSecondary,
                spinnerColor, true,
            )
        ) {
            binding.swipe.setColorSchemeColors(spinnerColor.data)
        }
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
                text = getString(R.string.crash_instruction)
                setPadding(48, 32, 48, 16)
                // Created in code, so the theme's fontFamily does not
                // apply — Urdu needs Nastaleeq explicitly (C-47).
                applyUrduFontIfNeeded(this)
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
                .setTitle(R.string.crash_title)
                .setView(content)
                .setPositiveButton(R.string.crash_copy) { _, _ ->
                    try {
                        val clipboard =
                            getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText("crash report", report),
                        )
                        ToastHelper.show(this, ToastHelper.Type.SUCCESS, R.string.crash_copied)
                    } catch (_: Exception) {
                        ToastHelper.show(this, ToastHelper.Type.ERROR, R.string.crash_copy_failed)
                    }
                }
                .setNegativeButton(R.string.crash_dismiss) { _, _ -> file.delete() }
                .show()
                .also { styleDialogButtons(it, destructive = false) }
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
            .also { styleDialogButtons(it, destructive = false) }
    }

    /** Drawer item visibility: "Sign out" shows only while the
     * owner is signed in; there is no visible owner entry point —
     * sign-in / console are reached only via the hidden version-tap
     * (onVersionTap). "Show featured app" only shows while the hero
     * is dismissed. */
    private fun updateDrawerMenu() {
        val signedIn = AuthManager.isOwnerSignedIn(this)
        val menu = binding.navView.menu
        menu.findItem(R.id.owner_sign_out)?.isVisible = signedIn
        menu.findItem(R.id.show_featured)?.isVisible = isHeroDismissed()
        // Mark the active language (C-14) and the active theme
        // (I-4.0.8) with the selected-item background, so the user can
        // see which locale / theme is active.
        val urdu = StoreApp.useUrdu()
        menu.findItem(R.id.language_urdu)?.isChecked = urdu
        menu.findItem(R.id.language_english)?.isChecked = !urdu
        val themeMode = getSharedPreferences(GoharStoreApp.PREFS_UI, MODE_PRIVATE)
            .getString(GoharStoreApp.KEY_THEME_MODE, GoharStoreApp.THEME_SYSTEM)
            ?: GoharStoreApp.THEME_SYSTEM
        menu.findItem(R.id.theme_dark)?.isChecked = themeMode == GoharStoreApp.THEME_DARK
        menu.findItem(R.id.theme_light)?.isChecked = themeMode == GoharStoreApp.THEME_LIGHT
        menu.findItem(R.id.theme_system)?.isChecked = themeMode == GoharStoreApp.THEME_SYSTEM
    }

    /** Drawer theme choice (I-4.0.8): save it, apply it, and recreate
     * so the new theme takes effect immediately. */
    private fun setThemeMode(mode: String) {
        getSharedPreferences(GoharStoreApp.PREFS_UI, MODE_PRIVATE).edit()
            .putString(GoharStoreApp.KEY_THEME_MODE, mode).apply()
        GoharStoreApp.applySavedThemeMode(this)
        updateDrawerMenu()
        recreate()
    }

    /** Hidden owner entry (owner decision 28 Nov ~03:17 PKT):
     * 5 taps on the drawer-header version, each within ~2 s of the
     * previous one, open the owner sign-in when signed out, or the
     * Developer Console when already signed in. The drawer closes
     * first; the gesture is silent until it triggers. */
    private fun onVersionTap() {
        val now = SystemClock.uptimeMillis()
        if (now - lastVersionTapTime > 2000) versionTapCount = 0
        lastVersionTapTime = now
        versionTapCount++
        if (versionTapCount < 5) return
        versionTapCount = 0
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        if (AuthManager.isOwnerSignedIn(this)) {
            startActivity(Intent(this, AdminActivity::class.java))
        } else {
            startActivity(Intent(this, AuthActivity::class.java))
        }
    }

    /** One tap in the drawer = one action, then the drawer closes.
     * Same actions the overflow menu used to run. */
    private fun handleDrawerItem(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.show_featured -> setHeroDismissed(false)
            R.id.about_author -> startActivity(Intent(this, AboutActivity::class.java))
            R.id.owner_sign_out -> {
                AuthManager.signOut(this)
                ToastHelper.show(this, ToastHelper.Type.INFO, R.string.signed_out)
                updateDrawerMenu()
            }
            R.id.language_urdu -> AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ur"))
            R.id.language_english -> AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
            R.id.theme_dark -> setThemeMode(GoharStoreApp.THEME_DARK)
            R.id.theme_light -> setThemeMode(GoharStoreApp.THEME_LIGHT)
            R.id.theme_system -> setThemeMode(GoharStoreApp.THEME_SYSTEM)
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
        skeletonAnimator?.cancel()
        skeletonAnimator = null
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
        // First load only: show the skeleton rows while the list
        // is still empty. Pull-to-refresh with data keeps the list and
        // just spins the swipe indicator.
        if (catalog.apps.isEmpty()) showSkeleton()
        binding.swipe.isRefreshing = true
        CatalogLoader.load(this) { loaded ->
            catalog = loaded
            binding.swipe.isRefreshing = false
            hideSkeleton()
            binding.sourceNote.text = if (loaded.fromRemote) {
                getString(R.string.online_catalogue, loaded.updated)
            } else {
                getString(R.string.offline_catalogue)
            }
            buildChips()
            refreshList()
        }
    }

    /** First-load skeleton (C-52, Phase 5 §G1): framework-only
     * gentle alpha pulse (0.55 ↔ 1.0, 1 s, reverse-repeat) — no new
     * dependency, no shimmer library. The load callback always runs
     * (CatalogLoader falls back to the bundled copy, then to an empty
     * catalogue), so hideSkeleton() runs on success, on error, and
     * on empty — the skeleton never sticks. */
    private fun showSkeleton() {
        binding.loadingState.visibility = View.VISIBLE
        skeletonAnimator?.cancel()
        skeletonAnimator = ObjectAnimator.ofFloat(
            binding.loadingState, View.ALPHA, 0.55f, 1f,
        ).apply {
            duration = 1000
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun hideSkeleton() {
        skeletonAnimator?.cancel()
        skeletonAnimator = null
        binding.loadingState.alpha = 1f
        binding.loadingState.visibility = View.GONE
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
            // Updates tab lists UPDATE_AVAILABLE apps ONLY (C-12):
            // up-to-date installed apps no longer appear here.
            list = list.filter { StoreActions.stateFor(this, it) == AppState.UPDATE_AVAILABLE }
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
        // The empty-state mark (C-16) rides with the empty-state text.
        binding.emptyStateMark.visibility = binding.emptyState.visibility
        binding.emptyState.text = when {
            // Updates empty state has two cases (C-12 / C-18): nothing
            // installed at all, or apps installed but none with an
            // update. The list is empty in both, so tell them apart by
            // whether any catalogue app is installed.
            tab == Tab.UPDATES -> {
                val anyInstalled = catalog.apps.any {
                    StoreActions.installedVersionCode(this, it.packageName) != null
                }
                if (anyInstalled) getString(R.string.no_updates_available)
                else getString(R.string.no_apps_installed)
            }
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
            if (installed != null) append("  " + getString(R.string.row_installed_suffix))
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
