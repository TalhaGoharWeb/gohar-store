package com.gohar.store

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.gohar.store.databinding.ActivityScreenshotViewerBinding
import com.gohar.store.databinding.ItemScreenshotFullBinding

/**
 * Full-screen screenshot viewer, Play Store style: swipe between
 * screenshots, a "3 / 10" counter, tap anywhere to close.
 */
class ScreenshotViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_APP_ID = "app_id"
        const val EXTRA_INDEX = "index"
    }

    private lateinit var binding: ActivityScreenshotViewerBinding
    private var screenshots: List<String> = emptyList()
    private lateinit var layoutManager: LinearLayoutManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScreenshotViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val appId = intent.getStringExtra(EXTRA_APP_ID) ?: ""
        val startIndex = intent.getIntExtra(EXTRA_INDEX, 0)

        layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.viewerList.layoutManager = layoutManager
        PagerSnapHelper().attachToRecyclerView(binding.viewerList)
        binding.viewerList.adapter = ViewerAdapter()
        binding.viewerList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                updateCounter()
            }
        })

        binding.viewerRoot.setOnClickListener { finish() }

        CatalogLoader.load(this) { catalog ->
            val app = catalog.apps.firstOrNull { it.id == appId }
            if (app == null || app.screenshots.isEmpty()) {
                finish()
                return@load
            }
            screenshots = app.screenshots
            binding.viewerList.adapter?.notifyDataSetChanged()
            val index = startIndex.coerceIn(0, screenshots.size - 1)
            binding.viewerList.scrollToPosition(index)
            updateCounter(index)
        }
    }

    private fun updateCounter(position: Int? = null) {
        if (screenshots.isEmpty()) return
        val pos = position ?: layoutManager.findFirstVisibleItemPosition()
            .coerceIn(0, screenshots.size - 1)
        binding.viewerCounter.text = "${pos + 1} / ${screenshots.size}"
    }

    private inner class ViewerAdapter : RecyclerView.Adapter<ViewerAdapter.Holder>() {
        inner class Holder(val binding: ItemScreenshotFullBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun getItemCount(): Int = screenshots.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val b = ItemScreenshotFullBinding.inflate(
                LayoutInflater.from(parent.context), parent, false,
            )
            return Holder(b)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val url = screenshots[position]
            holder.binding.fullImage.tag = url
            holder.binding.fullImage.visibility = android.view.View.VISIBLE
            ImageLoader.load(url, holder.binding.fullImage)
            holder.binding.root.setOnClickListener { finish() }
        }
    }
}
