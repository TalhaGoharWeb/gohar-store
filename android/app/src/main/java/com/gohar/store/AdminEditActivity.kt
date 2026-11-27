package com.gohar.store

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.gohar.store.databinding.ActivityAdminEditBinding

/**
 * The add/edit form for one catalogue entry. Everything is validated
 * here; the list in [AdminActivity] only holds the result until Talha
 * taps Publish. Nothing in this screen touches the network.
 *
 * The edited app is returned as JSON ([StoreApp.toJson]) via
 * [EXTRA_APP_JSON] so the caller never has to know this screen's
 * field layout.
 */
class AdminEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_APP_JSON = "app_json"
        const val EXTRA_IS_NEW = "is_new"
        const val EXTRA_EXISTING_IDS = "existing_ids"
        const val EXTRA_EXISTING_PACKAGES = "existing_packages"
        const val EXTRA_ORIGINAL_VERSION_CODE = "original_version_code"

        private val PACKAGE_REGEX =
            Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")
        private val COLOR_REGEX =
            Regex("^#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$")

        /** C-56: the five catalogue categories, English paired with
         * Urdu — the exact pairs in catalog.json. The dropdown only
         * suggests these; free text is still allowed. */
        private val CATEGORY_PAIRS = listOf(
            "Agriculture" to "زراعت",
            "Books & Reference" to "کتب و حوالہ",
            "Education" to "تعلیم",
            "Finance" to "مالیات",
            "Lifestyle" to "طرزِ زندگی",
        )
    }

    /** C-57: the form exactly as it opened (after bind + the C-56
     * pairing pre-fill), normalised the same way [collect] reads it —
     * trimmed text, blank lines dropped from multi-line lists — so a
     * trailing space or blank line never counts as a change. */
    private data class FormState(
        val id: String,
        val name: String,
        val nameUr: String,
        val developer: String,
        val packageName: String,
        val versionName: String,
        val versionCode: String,
        val sizeBytes: String,
        val minSdk: String,
        val category: String,
        val categoryUr: String,
        val tagline: String,
        val taglineUr: String,
        val description: String,
        val descriptionUr: String,
        val whatsNew: String,
        val whatsNewUr: String,
        val iconUrl: String,
        val iconColor: String,
        val iconColor2: String,
        val apkUrl: String,
        val apkUrlArm64: String,
        val apkUrlArmv7: String,
        val screenshots: String,
        val comingSoon: String,
        val comingSoonUr: String,
        val featured: Boolean,
    )

    private lateinit var binding: ActivityAdminEditBinding
    private var isNew = true
    private var originalVersionCode = 0L
    private var originalApp: StoreApp? = null
    private var existingIds: List<String> = emptyList()
    private var existingPackages: List<String> = emptyList()
    private var openedForm: FormState? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminEditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        isNew = intent.getBooleanExtra(EXTRA_IS_NEW, true)
        originalVersionCode = intent.getLongExtra(EXTRA_ORIGINAL_VERSION_CODE, 0)
        existingIds = intent.getStringArrayListExtra(EXTRA_EXISTING_IDS) ?: emptyList()
        existingPackages =
            intent.getStringArrayListExtra(EXTRA_EXISTING_PACKAGES) ?: emptyList()

        val app = intent.getStringExtra(EXTRA_APP_JSON)?.let { json ->
            runCatching {
                CatalogLoader.parse("{\"apps\":[$json]}").apps.firstOrNull()
            }.getOrNull()
        } ?: blankApp()
        originalApp = if (isNew) null else app

        supportActionBar?.title =
            getString(if (isNew) R.string.admin_new_app else R.string.admin_edit_app)
        setupCategoryDropdowns()
        bind(app)
        binding.saveButton.setOnClickListener { attemptSave() }

        // C-57: toolbar back and system back share this path —
        // onSupportNavigateUp routes through the dispatcher below.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (hasUnsavedChanges()) confirmDiscardEdit() else finish()
            }
        })
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    /** C-56: exposed dropdowns on both category fields. Picking an
     * entry fills the paired field with its counterpart; typing is
     * untouched — a custom value in either field never overwrites
     * the other, and any text (not just a listed pair) still saves.
     * Pairing happens only on a dropdown pick, never on keystrokes,
     * so typing an exact pair value by hand leaves the other field
     * alone. Look-up is by the picked string, not the list position,
     * so it stays correct when the list is filtered by typing. */
    private fun setupCategoryDropdowns() {
        binding.editCategory.setAdapter(
            categoryAdapter(CATEGORY_PAIRS.map { it.first }, urdu = false),
        )
        binding.editCategoryUr.setAdapter(
            categoryAdapter(CATEGORY_PAIRS.map { it.second }, urdu = true),
        )
        binding.editCategory.setOnItemClickListener { parent, _, position, _ ->
            val selected = parent.getItemAtPosition(position) as? String
                ?: return@setOnItemClickListener
            CATEGORY_PAIRS.firstOrNull { it.first == selected }?.let { pair ->
                binding.editCategoryUr.setText(pair.second, false)
            }
        }
        binding.editCategoryUr.setOnItemClickListener { parent, _, position, _ ->
            val selected = parent.getItemAtPosition(position) as? String
                ?: return@setOnItemClickListener
            CATEGORY_PAIRS.firstOrNull { it.second == selected }?.let { pair ->
                binding.editCategory.setText(pair.first, false)
            }
        }
    }

    private fun categoryAdapter(items: List<String>, urdu: Boolean) =
        object : ArrayAdapter<String>(
            this, android.R.layout.simple_list_item_1, items,
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                // Dropdown rows are created in code, so the Urdu list
                // needs the Nastaleeq font applied explicitly — the
                // same rule as applyUrduFontIfNeeded's callers.
                if (urdu) (view as? TextView)?.let { applyUrduFontIfNeeded(it) }
                return view
            }
        }

    /** C-57: mirrors the console's D-08 discard confirmation
     * (AdminActivity.confirmDiscard) — same strings, same button
     * styling, destructive = false. Discard finishes without saving;
     * Cancel dismisses and keeps editing. */
    private fun confirmDiscardEdit() {
        AlertDialog.Builder(this)
            .setTitle(R.string.admin_discard_title)
            .setMessage(R.string.admin_discard_message)
            .setPositiveButton(R.string.admin_discard) { _, _ -> finish() }
            .setNegativeButton(R.string.admin_cancel, null)
            .show()
            .also { styleDialogButtons(it, destructive = false) }
    }

    private fun hasUnsavedChanges(): Boolean {
        val opened = openedForm ?: return false
        return snapshotForm() != opened
    }

    private fun snapshotForm(): FormState = with(binding) {
        FormState(
            id = text(editId),
            name = text(editName),
            nameUr = text(editNameUr),
            developer = text(editDeveloper),
            packageName = text(editPackageName),
            versionName = text(editVersionName),
            versionCode = text(editVersionCode),
            sizeBytes = text(editSizeBytes),
            minSdk = text(editMinSdk),
            category = text(editCategory),
            categoryUr = text(editCategoryUr),
            tagline = text(editTagline),
            taglineUr = text(editTaglineUr),
            description = text(editDescription),
            descriptionUr = text(editDescriptionUr),
            whatsNew = lines(editWhatsNew).joinToString("\n"),
            whatsNewUr = lines(editWhatsNewUr).joinToString("\n"),
            iconUrl = text(editIconUrl),
            iconColor = text(editIconColor),
            iconColor2 = text(editIconColor2),
            apkUrl = text(editApkUrl),
            apkUrlArm64 = text(editApkUrlArm64),
            apkUrlArmv7 = text(editApkUrlArmv7),
            screenshots = lines(editScreenshots).joinToString("\n"),
            comingSoon = text(editComingSoon),
            comingSoonUr = text(editComingSoonUr),
            featured = featuredCheck.isChecked,
        )
    }

    private fun blankApp(): StoreApp = StoreApp(
        id = "",
        name = "",
        tagline = "",
        description = "",
        developer = "Talha Gohar",
        category = "",
        packageName = "",
        versionName = "",
        versionCode = 0,
        sizeBytes = 0,
        iconColor = "#0E9594",
        iconColor2 = "#0B2545",
        iconLetter = "?",
        iconUrl = null,
        screenshots = emptyList(),
        featured = false,
        whatsNew = emptyList(),
        apkUrl = null,
        apkUrlArm64 = null,
        apkUrlArmv7 = null,
        comingSoon = null,
        minSdk = 26,
        taglineUr = null,
        descriptionUr = null,
        categoryUr = null,
        whatsNewUr = emptyList(),
        comingSoonUr = null,
    )

    private fun bind(app: StoreApp) {
        with(binding) {
            editId.setText(app.id)
            editId.isEnabled = isNew
            idLockedNote.visibility = if (isNew) View.GONE else View.VISIBLE
            editName.setText(app.name)
            editNameUr.setText(app.nameUr ?: "")
            editDeveloper.setText(app.developer)
            editPackageName.setText(app.packageName)
            editVersionName.setText(app.versionName)
            editVersionCode.setText(if (app.versionCode > 0) app.versionCode.toString() else "")
            editSizeBytes.setText(if (app.sizeBytes > 0) app.sizeBytes.toString() else "")
            editCategory.setText(app.category, false)
            editCategoryUr.setText(app.categoryUr ?: "", false)
            // C-56 Edit pre-fill: a stored category shows as-is; when
            // its counterpart was never stored, fill the paired label
            // (e.g. Agriculture -> زراعت). A stored counterpart — even
            // one that is not the pair — is never overwritten.
            if (app.categoryUr.isNullOrBlank()) {
                CATEGORY_PAIRS.firstOrNull { it.first == app.category }?.let { pair ->
                    editCategoryUr.setText(pair.second, false)
                }
            }
            if (app.category.isBlank()) {
                CATEGORY_PAIRS.firstOrNull { it.second == app.categoryUr }?.let { pair ->
                    editCategory.setText(pair.first, false)
                }
            }
            editTagline.setText(app.tagline)
            editTaglineUr.setText(app.taglineUr ?: "")
            editDescription.setText(app.description)
            editDescriptionUr.setText(app.descriptionUr ?: "")
            editWhatsNew.setText(app.whatsNew.joinToString("\n"))
            editWhatsNewUr.setText(app.whatsNewUr.joinToString("\n"))
            editIconUrl.setText(app.iconUrl ?: "")
            editIconColor.setText(app.iconColor)
            editIconColor2.setText(app.iconColor2)
            editApkUrl.setText(app.apkUrl ?: "")
            editApkUrlArm64.setText(app.apkUrlArm64 ?: "")
            editApkUrlArmv7.setText(app.apkUrlArmv7 ?: "")
            editScreenshots.setText(app.screenshots.joinToString("\n"))
            editComingSoon.setText(app.comingSoon ?: "")
            editComingSoonUr.setText(app.comingSoonUr ?: "")
            editMinSdk.setText(app.minSdk.toString())
            featuredCheck.isChecked = app.featured
        }
        // Live icon preview: letter + colours follow the name fields,
        // the image follows the icon URL when the field loses focus.
        val letterWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = refreshLetter()
        }
        binding.editName.addTextChangedListener(letterWatcher)
        binding.editIconColor.addTextChangedListener(letterWatcher)
        binding.editIconColor2.addTextChangedListener(letterWatcher)
        binding.editIconUrl.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) refreshIconImage()
        }
        refreshLetter()
        refreshIconImage()
        // C-57: snapshot AFTER the pairing pre-fill above, so a
        // paired label filled in on open is not itself a "change".
        openedForm = snapshotForm()
    }

    private fun gradientFor(color1: String, color2: String): GradientDrawable {
        val c1 = runCatching { Color.parseColor(color1) }.getOrDefault(Color.parseColor("#0E9594"))
        val c2 = runCatching { Color.parseColor(color2) }.getOrDefault(Color.parseColor("#0B2545"))
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR, intArrayOf(c1, c2),
        ).apply { cornerRadius = 14f * resources.displayMetrics.density }
    }

    private fun refreshLetter() {
        val name = binding.editName.text?.toString()?.trim().orEmpty()
        binding.iconPreviewLetter.text =
            name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        binding.iconPreviewLetter.background =
            gradientFor(
                binding.editIconColor.text?.toString()?.trim().orEmpty(),
                binding.editIconColor2.text?.toString()?.trim().orEmpty(),
            )
    }

    private fun refreshIconImage() {
        val url = binding.editIconUrl.text?.toString()?.trim()
        binding.iconPreviewImage.contentDescription = binding.editName.text?.toString()
        ImageLoader.load(url, binding.iconPreviewImage, binding.iconPreviewLetter)
    }

    // ---- validation + save -------------------------------------------------

    private fun text(id: android.widget.EditText): String =
        id.text?.toString()?.trim().orEmpty()

    private fun lines(id: android.widget.EditText): List<String> =
        id.text?.toString()?.lines().orEmpty().map { it.trim() }.filter { it.isNotEmpty() }

    private fun optional(id: android.widget.EditText): String? =
        text(id).takeIf { it.isNotEmpty() }

    private fun isUrl(value: String): Boolean =
        value.startsWith("http://") || value.startsWith("https://")

    /** Returns the edited app, or null (with inline errors set) when
     * the form is not ready to save. */
    private fun collect(): StoreApp? {
        with(binding) {
            idLayout.error = null
            nameLayout.error = null
            packageLayout.error = null
            versionNameLayout.error = null
            versionCodeLayout.error = null
            sizeLayout.error = null
            minSdkLayout.error = null
            iconUrlLayout.error = null
            iconColorLayout.error = null
            iconColor2Layout.error = null
            apkUrlLayout.error = null
            apkUrlArm64Layout.error = null
            apkUrlArmv7Layout.error = null
            screenshotsLayout.error = null
        }
        var ok = true
        val b = binding

        val id = text(b.editId)
        if (isNew) {
            when {
                id.isEmpty() -> {
                    b.idLayout.error = getString(R.string.error_required); ok = false
                }
                id in existingIds -> {
                    b.idLayout.error = getString(R.string.error_duplicate_id); ok = false
                }
            }
        }
        val name = text(b.editName)
        if (name.isEmpty()) {
            b.nameLayout.error = getString(R.string.error_required); ok = false
        }
        val packageName = text(b.editPackageName)
        when {
            packageName.isEmpty() -> {
                b.packageLayout.error = getString(R.string.error_required); ok = false
            }
            !PACKAGE_REGEX.matches(packageName) -> {
                b.packageLayout.error = getString(R.string.error_package); ok = false
            }
            packageName != originalApp?.packageName && packageName in existingPackages -> {
                b.packageLayout.error = getString(R.string.error_duplicate_package); ok = false
            }
        }
        val versionName = text(b.editVersionName)
        if (versionName.isEmpty()) {
            b.versionNameLayout.error = getString(R.string.error_required); ok = false
        }
        val versionCode = text(b.editVersionCode).toLongOrNull()
        if (versionCode == null || versionCode <= 0) {
            b.versionCodeLayout.error = getString(R.string.error_version_code); ok = false
        }
        val sizeBytes = text(b.editSizeBytes).let {
            if (it.isEmpty()) 0L else it.toLongOrNull()
        }
        if (sizeBytes == null) {
            b.sizeLayout.error = getString(R.string.error_number); ok = false
        }
        val minSdk = text(b.editMinSdk).let {
            if (it.isEmpty()) 26 else it.toIntOrNull()
        }
        if (minSdk == null) {
            b.minSdkLayout.error = getString(R.string.error_number); ok = false
        }
        val iconColor = text(b.editIconColor).ifEmpty { "#0E9594" }
        if (!COLOR_REGEX.matches(iconColor)) {
            b.iconColorLayout.error = getString(R.string.error_color); ok = false
        }
        val iconColor2 = text(b.editIconColor2).ifEmpty { "#0B2545" }
        if (!COLOR_REGEX.matches(iconColor2)) {
            b.iconColor2Layout.error = getString(R.string.error_color); ok = false
        }
        for ((layout, field) in listOf(
            b.iconUrlLayout to b.editIconUrl,
            b.apkUrlLayout to b.editApkUrl,
            b.apkUrlArm64Layout to b.editApkUrlArm64,
            b.apkUrlArmv7Layout to b.editApkUrlArmv7,
        )) {
            val value = text(field)
            if (value.isNotEmpty() && !isUrl(value)) {
                layout.error = getString(R.string.error_url); ok = false
            }
        }
        val screenshots = lines(b.editScreenshots)
        if (screenshots.any { !isUrl(it) }) {
            b.screenshotsLayout.error = getString(R.string.error_url); ok = false
        }
        if (!ok) {
            ToastHelper.show(this, ToastHelper.Type.ERROR, R.string.admin_fix_errors)
            return null
        }
        return StoreApp(
            id = if (isNew) id else originalApp?.id.orEmpty(),
            name = name,
            nameUr = optional(b.editNameUr),
            tagline = text(b.editTagline),
            description = text(b.editDescription),
            developer = text(b.editDeveloper).ifEmpty { "Talha Gohar" },
            category = text(b.editCategory),
            packageName = packageName,
            versionName = versionName,
            versionCode = versionCode ?: 0,
            sizeBytes = sizeBytes ?: 0,
            iconColor = iconColor,
            iconColor2 = iconColor2,
            iconLetter = name.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
            iconUrl = optional(b.editIconUrl),
            screenshots = screenshots,
            featured = b.featuredCheck.isChecked,
            whatsNew = lines(b.editWhatsNew),
            apkUrl = optional(b.editApkUrl),
            apkUrlArm64 = optional(b.editApkUrlArm64),
            apkUrlArmv7 = optional(b.editApkUrlArmv7),
            comingSoon = optional(b.editComingSoon),
            minSdk = minSdk ?: 26,
            taglineUr = optional(b.editTaglineUr),
            descriptionUr = optional(b.editDescriptionUr),
            categoryUr = optional(b.editCategoryUr),
            whatsNewUr = lines(b.editWhatsNewUr),
            comingSoonUr = optional(b.editComingSoonUr),
        )
    }

    private fun attemptSave() {
        val app = collect() ?: return
        if (!isNew && app.versionCode <= originalVersionCode) {
            AlertDialog.Builder(this)
                .setTitle(R.string.admin_version_warning_title)
                .setMessage(
                    getString(
                        R.string.admin_version_warning,
                        app.versionCode, originalVersionCode,
                    ),
                )
                .setPositiveButton(R.string.admin_save_anyway) { _, _ -> finishWith(app) }
                .setNegativeButton(R.string.admin_go_back, null)
                .show()
                .also { styleDialogButtons(it, destructive = false) }
            return
        }
        finishWith(app)
    }

    private fun finishWith(app: StoreApp) {
        setResult(
            RESULT_OK,
            Intent().putExtra(EXTRA_APP_JSON, app.toJson().toString()),
        )
        finish()
    }
}
