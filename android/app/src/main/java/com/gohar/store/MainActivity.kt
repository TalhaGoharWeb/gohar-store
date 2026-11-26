package com.gohar.store

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.gohar.store.databinding.ActivityMainBinding
import com.gohar.store.databinding.ItemAppBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var catalog: Catalog = Catalog(emptyList(), "", false)
    private var query: String = ""
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            adapter.notifyDataSetChanged()
            updateFeatured()
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
                adapter.notifyDataSetChanged()
                updateFeatured()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.swipe.setOnRefreshListener { reload() }
        binding.swipe.setColorSchemeColors(Color.parseColor("#0E9594"))
        binding.refreshButton.setOnClickListener { reload() }

        reload()
    }

    override fun onResume() {
        super.onResume()
        adapter.notifyDataSetChanged()
        updateFeatured()
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
                "Updated ${loaded.updated} · online catalogue"
            } else {
                "Offline catalogue · check for updates to refresh"
            }
            adapter.notifyDataSetChanged()
            updateFeatured()
        }
    }

    private fun visibleApps(): List<StoreApp> {
        if (query.isEmpty()) return catalog.apps
        return catalog.apps.filter {
            it.name.contains(query, ignoreCase = true) ||
                it.tagline.contains(query, ignoreCase = true) ||
                it.category.contains(query, ignoreCase = true)
        }
    }

    private fun updateFeatured() {
        val featured = catalog.apps.firstOrNull { it.featured }
        if (featured == null || query.isNotEmpty()) {
            binding.featuredCard.visibility = View.GONE
            return
        }
        binding.featuredCard.visibility = View.VISIBLE
        binding.featuredIcon.text = featured.iconLetter
        binding.featuredIcon.background = circleDrawable(featured.iconColor)
        binding.featuredName.text = featured.name
        binding.featuredTagline.text = featured.tagline
        binding.featuredMeta.text =
            "v${featured.versionName} · ${featured.sizeLabel()} · ${featured.category}"
        binding.featuredButton.text = StoreActions.actionLabel(this, featured)
        binding.featuredButton.isEnabled =
            StoreActions.stateFor(this, featured) != AppState.DOWNLOADING
        binding.featuredCard.setOnClickListener { openDetail(featured) }
        binding.featuredButton.setOnClickListener {
            StoreActions.primaryAction(this, featured)
            adapter.notifyDataSetChanged()
            updateFeatured()
        }
    }

    private fun bindRow(b: ItemAppBinding, app: StoreApp) {
        b.appIcon.text = app.iconLetter
        b.appIcon.background = circleDrawable(app.iconColor)
        b.appName.text = app.name
        b.appTagline.text = app.tagline
        val installed = StoreActions.installedVersionCode(this, app.packageName)
        val state = StoreActions.stateFor(this, app)
        val meta = buildString {
            append("v${app.versionName}")
            if (app.sizeLabel().isNotEmpty()) append(" · ${app.sizeLabel()}")
            append(" · ${app.category}")
            if (installed != null) append(" · installed")
        }
        b.appMeta.text = meta
        b.appAction.text = StoreActions.actionLabel(this, app)
        b.appAction.isEnabled = state != AppState.DOWNLOADING && state != AppState.COMING_SOON
        if (state == AppState.UPDATE_AVAILABLE) {
            b.updateBadge.visibility = View.VISIBLE
        } else {
            b.updateBadge.visibility = View.GONE
        }
        b.root.setOnClickListener { openDetail(app) }
        b.appAction.setOnClickListener {
            StoreActions.primaryAction(this, app)
            adapter.notifyDataSetChanged()
            updateFeatured()
        }
    }

    private fun openDetail(app: StoreApp) {
        startActivity(Intent(this, DetailActivity::class.java).apply {
            putExtra(DetailActivity.EXTRA_APP_ID, app.id)
        })
    }

    companion object {
        fun circleDrawable(hexColor: String): GradientDrawable {
            val color = try {
                Color.parseColor(hexColor)
            } catch (_: Exception) {
                Color.parseColor("#0E9594")
            }
            return GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
        }
    }
}
