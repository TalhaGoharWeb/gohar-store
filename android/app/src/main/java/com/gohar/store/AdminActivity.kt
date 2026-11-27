package com.gohar.store

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputLayout
import com.gohar.store.databinding.ActivityAdminBinding
import com.gohar.store.databinding.ItemAdminAppBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The developer console: Talha's in-app way to add, edit and remove
 * apps from the store's catalogue.
 *
 * The catalogue is edited in memory and only goes live when Publish
 * is tapped, which PUTs catalog.json back to the talha-gohar-apps repo
 * through the GitHub Contents API (see [GitHubCatalog]). The store
 * itself picks the change up on its next refresh — no new store APK
 * is ever needed for a catalogue change.
 *
 * The GitHub token lives only in this phone's private preferences.
 * Without a working token the console can still *look* at the
 * store's current catalogue, but every edit control stays disabled.
 */
class AdminActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminBinding
    private var token: String? = null
    private var catalog: Catalog? = null
    private var apps: MutableList<StoreApp> = mutableListOf()
    private var sha: String? = null
    private var dirty = false
    private var readOnly = false
    private var publishing = false

    /** Null while an "add" edit is in flight, otherwise the id being edited. */
    private var pendingEditId: String? = null

    private val editLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val json = result.data?.getStringExtra(AdminEditActivity.EXTRA_APP_JSON) ?: return@registerForActivityResult
        val app = runCatching {
            CatalogLoader.parse("{\"apps\":[$json]}").apps.firstOrNull()
        }.getOrNull() ?: return@registerForActivityResult
        val editingId = pendingEditId
        if (editingId == null) {
            apps += app
        } else {
            val index = apps.indexOfFirst { it.id == editingId }
            if (index >= 0) apps[index] = app
        }
        if (app.featured) {
            apps.forEachIndexed { i, other ->
                if (other.id != app.id && other.featured) apps[i] = other.copy(featured = false)
            }
        }
        pendingEditId = null
        dirty = true
        updateStatus()
        adapter.notifyDataSetChanged()
        invalidateOptionsMenu()
    }

    private inner class AdminViewHolder(val b: ItemAdminAppBinding) :
        RecyclerView.ViewHolder(b.root)

    private val adapter = object : RecyclerView.Adapter<AdminViewHolder>() {
        override fun getItemCount(): Int = apps.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AdminViewHolder {
            val b = ItemAdminAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return AdminViewHolder(b)
        }

        override fun onBindViewHolder(holder: AdminViewHolder, position: Int) {
            bindRow(holder.b, apps[position])
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Owner-only gate: the console must never open for anyone
        // who is not signed in as the owner.
        if (!AuthManager.isOwnerSignedIn(this)) {
            Toast.makeText(this, R.string.owner_sign_in_required, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        binding = ActivityAdminBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
        binding.retryButton.setOnClickListener { loadRemote() }
        binding.useCurrentButton.setOnClickListener { loadFallback() }
        binding.addButton.setOnClickListener { launchEditor(null) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (dirty) confirmDiscard { finish() } else finish()
            }
        })

        token = GitHubCatalog.getToken(this)
        if (token == null) {
            showTokenDialog(firstTime = true, onCancel = { finish() })
        } else {
            loadRemote()
        }
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        // Signed out while this screen was open (e.g. from the home
        // screen menu): close the console instead of leaving it open.
        if (!AuthManager.isOwnerSignedIn(this)) {
            finish()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.admin_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_publish)?.isEnabled = dirty && !readOnly && sha != null && !publishing
        menu.findItem(R.id.action_discard)?.isEnabled = dirty && !publishing
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_publish -> publish()
            R.id.action_discard -> confirmDiscard { loadRemote() }
            R.id.action_clear_token -> {
                GitHubCatalog.clearToken(this)
                Toast.makeText(this, R.string.admin_token_cleared, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    // ---- loading -----------------------------------------------------------

    private fun loadRemote() {
        val t = token ?: run {
            showTokenDialog(firstTime = true, onCancel = { finish() })
            return
        }
        readOnly = false
        binding.loading.visibility = View.VISIBLE
        binding.errorView.visibility = View.GONE
        binding.recycler.visibility = View.GONE
        binding.emptyState.visibility = View.GONE
        GitHubCatalog.load(t) { result ->
            binding.loading.visibility = View.GONE
            result.onSuccess { remote ->
                catalog = remote.catalog
                sha = remote.sha
                apps = remote.catalog.apps.toMutableList()
                dirty = false
                binding.recycler.visibility = View.VISIBLE
                binding.emptyState.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
                adapter.notifyDataSetChanged()
                updateStatus()
                invalidateOptionsMenu()
            }.onFailure { error ->
                binding.errorText.text =
                    "${getString(R.string.admin_load_error)}\n${error.message}"
                binding.errorView.visibility = View.VISIBLE
                updateStatus()
            }
        }
    }

    /** Read-only fallback: the catalogue the store itself is showing
     * right now. Edits stay disabled until the API load succeeds. */
    private fun loadFallback() {
        binding.errorView.visibility = View.GONE
        binding.loading.visibility = View.VISIBLE
        CatalogLoader.load(this) { loaded ->
            binding.loading.visibility = View.GONE
            catalog = loaded
            apps = loaded.apps.toMutableList()
            sha = null
            dirty = false
            readOnly = true
            binding.recycler.visibility = View.VISIBLE
            binding.emptyState.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
            adapter.notifyDataSetChanged()
            updateStatus()
            invalidateOptionsMenu()
        }
    }

    private fun updateStatus() {
        val t = token
        val signedIn = if (t != null) getString(R.string.admin_signed_in_as, GitHubCatalog.tokenTail(t)) else ""
        val cat = catalog
        val appsLine = when {
            readOnly -> getString(R.string.admin_status_readonly)
            cat == null -> ""
            dirty -> getString(R.string.admin_status_dirty, apps.size, cat.updated)
            else -> getString(R.string.admin_status_apps, apps.size, cat.updated)
        }
        binding.statusLine.text = listOf(signedIn, appsLine)
            .filter { it.isNotEmpty() }.joinToString("\n")
        binding.addButton.isEnabled = !readOnly && catalog != null
    }

    // ---- token setup -------------------------------------------------------

    private fun showTokenDialog(firstTime: Boolean, onCancel: () -> Unit) {
        val context = this
        val inputLayout = TextInputLayout(context).apply {
            hint = getString(R.string.admin_token_hint)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        }
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        inputLayout.addView(input)
        val message = TextView(context).apply {
            text = getString(R.string.admin_token_message)
            textSize = 14f
            setPadding(0, 0, 0, 24)
            // Created in code — the Urdu theme's fontFamily does not
            // apply, so set Nastaleeq explicitly in the Urdu locale.
            applyUrduFontIfNeeded(this)
        }
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
            addView(message)
            addView(inputLayout)
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.admin_token_title)
            .setView(layout)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(R.string.admin_cancel) { _, _ -> onCancel() }
            .setCancelable(!firstTime)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = input.text?.toString()?.trim().orEmpty()
            if (value.isEmpty()) {
                inputLayout.error = getString(R.string.admin_token_required)
                return@setOnClickListener
            }
            GitHubCatalog.saveToken(context, value)
            token = value
            Toast.makeText(context, R.string.admin_token_saved, Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            loadRemote()
        }
        dialog.setOnCancelListener { onCancel() }
    }

    // ---- rows --------------------------------------------------------------

    private fun bindRow(b: ItemAdminAppBinding, app: StoreApp) {
        b.appIcon.text = app.iconLetter
        b.appIcon.background = MainActivity.gradientDrawable(app, 14f)
        b.appIcon.visibility = View.VISIBLE
        b.appIconImage.visibility = View.GONE
        b.appIconImage.contentDescription = app.name
        ImageLoader.load(app.iconUrl, b.appIconImage, b.appIcon)
        b.appName.text = app.name
        b.appMeta.text = buildString {
            append("v${app.versionName}")
            if (app.sizeLabel().isNotEmpty()) append("  ·  ${app.sizeLabel()}")
            if (app.featured) append("  ·  Featured")
        }
        b.appPackage.text = app.packageName
        val editable = !readOnly
        b.updateButton.visibility = if (editable) View.VISIBLE else View.GONE
        b.editButton.visibility = if (editable) View.VISIBLE else View.GONE
        b.deleteButton.visibility = if (editable) View.VISIBLE else View.GONE
        b.updateButton.setOnClickListener { showManualUpdateDialog(app) }
        b.editButton.setOnClickListener { launchEditor(app) }
        b.deleteButton.setOnClickListener { confirmDelete(app) }
    }

    // ---- manual update (quick version bump) ------------------------------

    /**
     * The quick "Update" action: version + APK links + what's new
     * only, prefilled from the app. Everything else (name, package,
     * icon, screenshots, Urdu text…) is left exactly as it is — use
     * full Edit for those. Like every console change, this only
     * stages the new version in memory; nothing goes live until
     * Publish is tapped.
     */
    private fun showManualUpdateDialog(app: StoreApp) {
        val context = this
        fun field(hintRes: Int, inputType: Int, initial: String): Pair<TextInputLayout, EditText> {
            val layout = TextInputLayout(context).apply {
                hint = getString(hintRes)
                boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            }
            val input = EditText(context).apply {
                this.inputType = inputType
                setText(initial)
            }
            layout.addView(input)
            return layout to input
        }
        val (versionNameLayout, versionNameInput) = field(
            R.string.field_version_name,
            InputType.TYPE_CLASS_TEXT,
            app.versionName,
        )
        val (versionCodeLayout, versionCodeInput) = field(
            R.string.field_version_code,
            InputType.TYPE_CLASS_NUMBER,
            if (app.versionCode > 0) app.versionCode.toString() else "",
        )
        val (apkUrlLayout, apkUrlInput) = field(
            R.string.field_apk_url,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            app.apkUrl ?: "",
        )
        val (apkUrlArm64Layout, apkUrlArm64Input) = field(
            R.string.field_apk_url_arm64,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            app.apkUrlArm64 ?: "",
        )
        val (apkUrlArmv7Layout, apkUrlArmv7Input) = field(
            R.string.field_apk_url_armv7,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            app.apkUrlArmv7 ?: "",
        )
        val (sizeLayout, sizeInput) = field(
            R.string.admin_field_size_mb,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL,
            if (app.sizeBytes > 0) "%.1f".format(app.sizeBytes / 1_048_576.0) else "",
        )
        val (whatsNewLayout, whatsNewInput) = field(
            R.string.field_whats_new,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
            app.whatsNew.joinToString("\n"),
        )
        val (whatsNewUrLayout, whatsNewUrInput) = field(
            R.string.field_whats_new_ur,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
            app.whatsNewUr.joinToString("\n"),
        )
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
            listOf(
                versionNameLayout, versionCodeLayout, apkUrlLayout,
                apkUrlArm64Layout, apkUrlArmv7Layout, sizeLayout,
                whatsNewLayout, whatsNewUrLayout,
            ).forEach { child ->
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = 24 }
                addView(child, params)
            }
        }
        val scroll = android.widget.ScrollView(context).apply { addView(layout) }
        val dialog = AlertDialog.Builder(context)
            .setTitle(getString(R.string.admin_manual_update_title, app.name))
            .setView(scroll)
            .setPositiveButton(R.string.admin_save_app, null)
            .setNegativeButton(R.string.admin_cancel, null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val updated = collectManualUpdate(
                app,
                versionNameLayout to versionNameInput,
                versionCodeLayout to versionCodeInput,
                apkUrlLayout to apkUrlInput,
                apkUrlArm64Layout to apkUrlArm64Input,
                apkUrlArmv7Layout to apkUrlArmv7Input,
                sizeLayout to sizeInput,
                whatsNewLayout to whatsNewInput,
                whatsNewUrLayout to whatsNewUrInput,
            ) ?: return@setOnClickListener
            // Same guard as full Edit: a versionCode that does not
            // rise is not an update for users — confirm explicitly.
            if (updated.versionCode <= app.versionCode) {
                AlertDialog.Builder(context)
                    .setTitle(R.string.admin_version_warning_title)
                    .setMessage(
                        getString(
                            R.string.admin_version_warning,
                            updated.versionCode, app.versionCode,
                        ),
                    )
                    .setPositiveButton(R.string.admin_save_anyway) { _, _ ->
                        applyManualUpdate(app, updated)
                        dialog.dismiss()
                    }
                    .setNegativeButton(R.string.admin_go_back, null)
                    .show()
                return@setOnClickListener
            }
            applyManualUpdate(app, updated)
            dialog.dismiss()
        }
    }

    /**
     * Validates the manual-update fields. Returns the updated app, or
     * null (with inline errors set) when the form is not ready.
     */
    private fun collectManualUpdate(
        app: StoreApp,
        versionName: Pair<TextInputLayout, EditText>,
        versionCode: Pair<TextInputLayout, EditText>,
        apkUrl: Pair<TextInputLayout, EditText>,
        apkUrlArm64: Pair<TextInputLayout, EditText>,
        apkUrlArmv7: Pair<TextInputLayout, EditText>,
        sizeMb: Pair<TextInputLayout, EditText>,
        whatsNew: Pair<TextInputLayout, EditText>,
        whatsNewUr: Pair<TextInputLayout, EditText>,
    ): StoreApp? {
        fun text(pair: Pair<TextInputLayout, EditText>): String =
            pair.second.text?.toString()?.trim().orEmpty()
        fun lines(pair: Pair<TextInputLayout, EditText>): List<String> =
            pair.second.text?.toString()?.lines().orEmpty()
                .map { it.trim() }.filter { it.isNotEmpty() }
        fun isUrl(value: String): Boolean =
            value.startsWith("http://") || value.startsWith("https://")
        listOf(
            versionName, versionCode, apkUrl, apkUrlArm64, apkUrlArmv7,
            sizeMb, whatsNew, whatsNewUr,
        ).forEach { it.first.error = null }
        var ok = true
        val newVersionName = text(versionName)
        if (newVersionName.isEmpty()) {
            versionName.first.error = getString(R.string.error_required); ok = false
        }
        val newVersionCode = text(versionCode).toLongOrNull()
        if (newVersionCode == null || newVersionCode <= 0) {
            versionCode.first.error = getString(R.string.error_version_code); ok = false
        }
        val urls = listOf(apkUrl, apkUrlArm64, apkUrlArmv7).map { text(it) }
        if (urls.all { it.isEmpty() }) {
            apkUrl.first.error = getString(R.string.admin_error_apk_required); ok = false
        }
        listOf(apkUrl, apkUrlArm64, apkUrlArmv7).zip(urls).forEach { (pair, value) ->
            if (value.isNotEmpty() && !isUrl(value)) {
                pair.first.error = getString(R.string.error_url); ok = false
            }
        }
        val sizeText = text(sizeMb)
        val sizeBytes = when {
            sizeText.isEmpty() -> 0L
            else -> sizeText.toDoubleOrNull()?.takeIf { it >= 0 }
                ?.let { (it * 1_048_576).toLong() }
        }
        if (sizeBytes == null) {
            sizeMb.first.error = getString(R.string.error_number); ok = false
        }
        if (!ok) {
            Toast.makeText(this, R.string.admin_fix_errors, Toast.LENGTH_SHORT).show()
            return null
        }
        return app.copy(
            versionName = newVersionName,
            versionCode = newVersionCode ?: app.versionCode,
            apkUrl = urls[0].takeIf { it.isNotEmpty() },
            apkUrlArm64 = urls[1].takeIf { it.isNotEmpty() },
            apkUrlArmv7 = urls[2].takeIf { it.isNotEmpty() },
            sizeBytes = sizeBytes ?: app.sizeBytes,
            whatsNew = lines(whatsNew),
            whatsNewUr = lines(whatsNewUr),
        )
    }

    private fun applyManualUpdate(original: StoreApp, updated: StoreApp) {
        val index = apps.indexOfFirst { it.id == original.id }
        if (index < 0) return
        apps[index] = updated
        dirty = true
        updateStatus()
        adapter.notifyDataSetChanged()
        invalidateOptionsMenu()
        Toast.makeText(this, R.string.admin_manual_update_ready, Toast.LENGTH_LONG).show()
    }

    private fun launchEditor(app: StoreApp?) {
        pendingEditId = app?.id
        val intent = Intent(this, AdminEditActivity::class.java).apply {
            putExtra(AdminEditActivity.EXTRA_IS_NEW, app == null)
            putExtra(
                AdminEditActivity.EXTRA_EXISTING_IDS,
                ArrayList(apps.map { it.id }),
            )
            putExtra(
                AdminEditActivity.EXTRA_EXISTING_PACKAGES,
                ArrayList(apps.map { it.packageName }),
            )
            putExtra(AdminEditActivity.EXTRA_ORIGINAL_VERSION_CODE, app?.versionCode ?: 0)
            if (app != null) putExtra(AdminEditActivity.EXTRA_APP_JSON, app.toJson().toString())
        }
        editLauncher.launch(intent)
    }

    private fun confirmDelete(app: StoreApp) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.admin_delete_title, app.name))
            .setMessage(R.string.admin_delete_message)
            .setPositiveButton(R.string.admin_delete_confirm) { _, _ ->
                apps.removeAll { it.id == app.id }
                dirty = true
                updateStatus()
                adapter.notifyDataSetChanged()
                invalidateOptionsMenu()
            }
            .setNegativeButton(R.string.admin_cancel, null)
            .show()
    }

    private fun confirmDiscard(onDiscard: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(R.string.admin_discard_title)
            .setMessage(R.string.admin_discard_message)
            .setPositiveButton(R.string.admin_discard) { _, _ -> onDiscard() }
            .setNegativeButton(R.string.admin_cancel, null)
            .show()
    }

    // ---- publish -----------------------------------------------------------

    private fun publish() {
        val t = token
        val currentSha = sha
        val currentCatalog = catalog
        if (t == null || currentSha == null || currentCatalog == null) return
        if (!dirty) {
            Toast.makeText(this, R.string.admin_no_changes, Toast.LENGTH_SHORT).show()
            return
        }
        publishing = true
        invalidateOptionsMenu()
        Toast.makeText(this, R.string.admin_publishing, Toast.LENGTH_SHORT).show()
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val toPublish = currentCatalog.copy(apps = apps.toList(), updated = today)
        GitHubCatalog.publish(t, toPublish, currentSha) { result ->
            publishing = false
            result.onSuccess { published ->
                sha = published.newSha
                catalog = toPublish
                dirty = false
                updateStatus()
                adapter.notifyDataSetChanged()
                invalidateOptionsMenu()
                val short = published.commitSha.take(7)
                Toast.makeText(
                    this,
                    getString(R.string.admin_published, short),
                    Toast.LENGTH_LONG,
                ).show()
                // Warm the store's own catalogue so the home screen
                // picks the change up on its next refresh.
                CatalogLoader.load(this) { }
            }.onFailure { error ->
                invalidateOptionsMenu()
                val message = error.message.orEmpty()
                if (message.contains("reload first", ignoreCase = true)) {
                    AlertDialog.Builder(this)
                        .setTitle(R.string.admin_reload_first)
                        .setMessage(message)
                        .setPositiveButton(R.string.admin_retry) { _, _ -> loadRemote() }
                        .setNegativeButton(R.string.admin_cancel, null)
                        .show()
                } else {
                    Toast.makeText(
                        this,
                        "${getString(R.string.admin_publish_failed)}: $message",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }
}
