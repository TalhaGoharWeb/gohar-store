package com.gohar.store

import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.gohar.store.databinding.ActivityScreenshotViewerBinding
import com.gohar.store.databinding.ItemScreenshotFullBinding
import com.google.android.material.card.MaterialCardView

/**
 * Full-screen screenshot viewer, Play Store style: swipe between
 * screenshots, a "3 / 10" counter, tap anywhere to close.
 *
 * C-58 (v1.4.0): pinch zoom + pan, double-tap zoom toggle, and a
 * thumbnail strip — framework gesture detectors only, no new
 * image-library dependency.
 *
 * Zoom model (per page holder — each page owns its own matrix):
 * - The page ImageView stays `fitCenter` until its bitmap is actually
 *   loaded; only then does it switch to `Matrix` scaleType with a base
 *   matrix equal to the fitCenter result. This matters because
 *   ImageLoader loads asynchronously with no completion callback, so
 *   there is no safe moment to build the base matrix before the
 *   drawable exists — and with `Matrix` scaleType and no matrix set,
 *   the image would draw unscaled at the top-left.
 * - Pinch: ScaleGestureDetector, 1x–4x relative to the fitCenter base.
 * - Double-tap: toggles 1x <-> 2x, centred on the tap point.
 * - Pan: one-finger drag while zoomed (>1x); translation is clamped
 *   so the image edge never leaves the view edge (and a dimension
 *   still smaller than the view stays centred, i.e. no pan there).
 * - While zoomed, the holder calls requestDisallowInterceptTouchEvent
 *   so the paging RecyclerView cannot steal the drag; at 1x no
 *   disallow is issued and swipe paging works exactly as before.
 * - Tap-to-close is GestureDetector.onSingleTapConfirmed (a single
 *   tap closes, a double-tap zooms — the two are distinguished by the
 *   detector, which is why the old immediate click listeners on the
 *   page root had to go: the first tap of a double-tap used to
 *   finish() the Activity before the second tap arrived). The close
 *   now lands ~one double-tap timeout (~300ms) after the tap.
 *   viewerRoot keeps its click listener for taps that never reach a
 *   page ImageView (e.g. on the counter chip overlay); image taps
 *   never reach it, so it cannot race a double-tap.
 * - Zoom resets whenever the page changes (scroll listener, via
 *   updateCounter) and whenever a holder is rebound to another page.
 */
class ScreenshotViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_APP_ID = "app_id"
        const val EXTRA_INDEX = "index"

        /** Pinch zoom range, relative to the fitCenter base (1x). */
        private const val MIN_SCALE = 1f

        /** Double-tap target: 2x, centred on the tap point. */
        private const val DOUBLE_TAP_SCALE = 2f

        /** Pinch zoom ceiling. */
        private const val MAX_SCALE = 4f
    }

    private lateinit var binding: ActivityScreenshotViewerBinding
    private var screenshots: List<String> = emptyList()
    private lateinit var layoutManager: LinearLayoutManager
    private val thumbAdapter = ThumbAdapter()

    /** Last page the counter/selection reflected (-1 = none yet). */
    private var currentPosition = RecyclerView.NO_POSITION

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

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

        // Thumbnail strip (C-58): horizontal, same screenshot list.
        // It is GONE until the catalogue loads, so it never shows an
        // empty bar while the viewer is still resolving the app.
        binding.thumbStrip.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.thumbStrip.adapter = thumbAdapter

        // Tap-anywhere-to-close for taps that never reach a page
        // ImageView (e.g. on the counter chip overlay). Image taps are
        // consumed by the page holder and close via SingleTapConfirmed
        // instead — see the class doc: an immediate click listener here
        // would race double-tap zoom. The ✕ button keeps its own
        // listener and finishes exactly as before.
        binding.viewerRoot.setOnClickListener { finish() }
        binding.viewerClose.setOnClickListener { finish() }

        CatalogLoader.load(this) { catalog ->
            val app = catalog.apps.firstOrNull { it.id == appId }
            if (app == null || app.screenshots.isEmpty()) {
                finish()
                return@load
            }
            screenshots = app.screenshots
            binding.viewerList.adapter?.notifyDataSetChanged()
            thumbAdapter.notifyDataSetChanged()
            binding.thumbStrip.visibility = View.VISIBLE
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
        if (pos != currentPosition) {
            currentPosition = pos
            // Zoom never carries across a page change.
            resetZoomOnVisibleHolders()
            thumbAdapter.setSelected(pos)
        }
    }

    /** Resets zoom on every currently attached page holder. */
    private fun resetZoomOnVisibleHolders() {
        for (i in 0 until binding.viewerList.childCount) {
            val holder = binding.viewerList.getChildViewHolder(binding.viewerList.getChildAt(i))
            if (holder is ViewerAdapter.Holder) holder.resetToBase()
        }
    }

    /** Thumbnail tap: reset zoom, then page to that screenshot. */
    private fun onThumbClicked(position: Int) {
        if (position !in screenshots.indices) return
        resetZoomOnVisibleHolders()
        binding.viewerList.smoothScrollToPosition(position)
        // Counter + strip selection follow the scroll via updateCounter().
    }

    private inner class ViewerAdapter : RecyclerView.Adapter<ViewerAdapter.Holder>() {
        inner class Holder(val binding: ItemScreenshotFullBinding) :
            RecyclerView.ViewHolder(binding.root) {

            private val imageView: ImageView = binding.fullImage

            /** Current transform (base * user zoom/pan). */
            private val imageMatrix = Matrix()

            /** fitCenter transform for the current drawable. */
            private val baseMatrix = Matrix()

            /** Zoom factor relative to the fitCenter base (1x). */
            private var userScale = MIN_SCALE

            /** Drawable the base matrix was built for; null = rebuild. */
            private var lastDrawable: Drawable? = null

            private var lastX = 0f
            private var lastY = 0f

            val isZoomed: Boolean get() = userScale > MIN_SCALE + 0.001f

            private val scaleDetector = ScaleGestureDetector(
                itemView.context,
                object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScaleBegin(detector: ScaleGestureDetector): Boolean = true

                    override fun onScale(detector: ScaleGestureDetector): Boolean {
                        if (!ensureBaseMatrix()) return false
                        val newScale = (userScale * detector.scaleFactor)
                            .coerceIn(MIN_SCALE, MAX_SCALE)
                        val factor = newScale / userScale
                        if (factor != 1f) {
                            imageMatrix.postScale(
                                factor, factor, detector.focusX, detector.focusY,
                            )
                            userScale = newScale
                            if (userScale <= MIN_SCALE + 0.001f) {
                                resetToBase()
                            } else {
                                fixBounds()
                                applyMatrix()
                            }
                        }
                        return true
                    }

                    override fun onScaleEnd(detector: ScaleGestureDetector) {
                        if (userScale <= MIN_SCALE + 0.001f) resetToBase()
                    }
                },
            )

            private val gestureDetector = GestureDetector(
                itemView.context,
                object : GestureDetector.SimpleOnGestureListener() {
                    // Must return true or no later gesture callbacks fire.
                    override fun onDown(e: MotionEvent): Boolean = true

                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                        this@ScreenshotViewerActivity.finish()
                        return true
                    }

                    override fun onDoubleTap(e: MotionEvent): Boolean {
                        toggleZoom(e.x, e.y)
                        return true
                    }
                },
            )

            init {
                // fitCenter until the bitmap exists (see class doc);
                // ensureBaseMatrix() switches to MATRIX once it can.
                imageView.scaleType = ImageView.ScaleType.FIT_CENTER
                imageView.setOnTouchListener { v, event -> onImageTouch(v, event) }
                imageView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    ensureBaseMatrix()
                }
            }

            private fun onImageTouch(v: View, event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    ensureBaseMatrix()
                }
                scaleDetector.onTouchEvent(event)
                gestureDetector.onTouchEvent(event)
                if (isZoomed || scaleDetector.isInProgress) {
                    // While zoomed (or mid-pinch) the paging
                    // RecyclerView must not steal the drag.
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        lastX = event.x
                        lastY = event.y
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (isZoomed && !scaleDetector.isInProgress &&
                            event.pointerCount == 1
                        ) {
                            panBy(event.x - lastX, event.y - lastY)
                        }
                        lastX = event.x
                        lastY = event.y
                    }
                    MotionEvent.ACTION_POINTER_UP -> {
                        // Keep panning from the finger still down.
                        if (event.pointerCount >= 2) {
                            val idx = if (event.actionIndex == 0) 1 else 0
                            lastX = event.getX(idx)
                            lastY = event.getY(idx)
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                        if (!isZoomed) resetToBase()
                    }
                }
                // Always true: returning false on ACTION_DOWN would
                // stop this view receiving the rest of the gesture,
                // which would break double-tap and pinch detection.
                // Paging still works at 1x because the parent
                // RecyclerView intercepts the MOVE before dispatch
                // (no disallow is issued at 1x).
                return true
            }

            /**
             * Builds the fitCenter base matrix for the current drawable
             * and switches the ImageView to MATRIX scaleType. No-op when
             * there is no drawable yet, the view is not laid out, or the
             * base is already built for this drawable (so it never
             * clobbers an in-progress zoom).
             */
            fun ensureBaseMatrix(): Boolean {
                val drawable = imageView.drawable ?: return false
                val viewWidth = imageView.width
                val viewHeight = imageView.height
                if (viewWidth == 0 || viewHeight == 0) return false
                val drawableWidth = drawable.intrinsicWidth
                val drawableHeight = drawable.intrinsicHeight
                if (drawableWidth <= 0 || drawableHeight <= 0) return false
                if (drawable === lastDrawable &&
                    imageView.scaleType == ImageView.ScaleType.MATRIX
                ) {
                    return true
                }
                val scale = minOf(
                    viewWidth.toFloat() / drawableWidth,
                    viewHeight.toFloat() / drawableHeight,
                )
                baseMatrix.setScale(scale, scale)
                baseMatrix.postTranslate(
                    (viewWidth - drawableWidth * scale) / 2f,
                    (viewHeight - drawableHeight * scale) / 2f,
                )
                imageMatrix.set(baseMatrix)
                userScale = MIN_SCALE
                lastDrawable = drawable
                imageView.scaleType = ImageView.ScaleType.MATRIX
                applyMatrix()
                return true
            }

            /** Back to exactly 1x (the fitCenter base). */
            fun resetToBase() {
                userScale = MIN_SCALE
                if (imageView.drawable == null ||
                    imageView.width == 0 || imageView.height == 0
                ) {
                    imageView.scaleType = ImageView.ScaleType.FIT_CENTER
                    return
                }
                lastDrawable = null // force ensureBaseMatrix() to rebuild
                ensureBaseMatrix()
            }

            /** Full reset for a recycled holder bound to another page. */
            fun resetForBind() {
                userScale = MIN_SCALE
                lastDrawable = null
                imageMatrix.reset()
                imageView.scaleType = ImageView.ScaleType.FIT_CENTER
                imageView.imageMatrix = Matrix()
            }

            private fun toggleZoom(focusX: Float, focusY: Float) {
                if (!ensureBaseMatrix()) return
                if (isZoomed) {
                    resetToBase()
                    return
                }
                imageMatrix.postScale(
                    DOUBLE_TAP_SCALE, DOUBLE_TAP_SCALE, focusX, focusY,
                )
                userScale = DOUBLE_TAP_SCALE
                fixBounds()
                applyMatrix()
            }

            private fun panBy(dx: Float, dy: Float) {
                if (!isZoomed) return
                imageMatrix.postTranslate(dx, dy)
                fixBounds()
                applyMatrix()
            }

            /**
             * Clamps the transformed image inside the view: an edge
             * that would leave empty space is pulled back, and a
             * dimension still narrower/shorter than the view is
             * re-centred (no pan in that dimension).
             */
            private fun fixBounds() {
                val drawable = imageView.drawable ?: return
                if (drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) return
                val viewWidth = imageView.width.toFloat()
                val viewHeight = imageView.height.toFloat()
                if (viewWidth == 0f || viewHeight == 0f) return
                val rect = RectF(
                    0f, 0f,
                    drawable.intrinsicWidth.toFloat(),
                    drawable.intrinsicHeight.toFloat(),
                )
                imageMatrix.mapRect(rect)
                var dx = 0f
                var dy = 0f
                if (rect.width() <= viewWidth) {
                    dx = viewWidth / 2f - rect.centerX()
                } else if (rect.left > 0f) {
                    dx = -rect.left
                } else if (rect.right < viewWidth) {
                    dx = viewWidth - rect.right
                }
                if (rect.height() <= viewHeight) {
                    dy = viewHeight / 2f - rect.centerY()
                } else if (rect.top > 0f) {
                    dy = -rect.top
                } else if (rect.bottom < viewHeight) {
                    dy = viewHeight - rect.bottom
                }
                if (dx != 0f || dy != 0f) imageMatrix.postTranslate(dx, dy)
            }

            private fun applyMatrix() {
                imageView.imageMatrix = imageMatrix
            }
        }

        override fun getItemCount(): Int = screenshots.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val b = ItemScreenshotFullBinding.inflate(
                LayoutInflater.from(parent.context), parent, false,
            )
            return Holder(b)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val url = screenshots[position]
            // A recycled holder never carries its old page's zoom.
            holder.resetForBind()
            holder.binding.fullImage.tag = url
            holder.binding.fullImage.visibility = View.VISIBLE
            ImageLoader.load(url, holder.binding.fullImage)
            // If the bitmap is already cached, the base matrix can be
            // built now; otherwise the image draws fitCenter until the
            // bitmap arrives and the first touch (or a layout change)
            // builds the base — visually identical at 1x.
            holder.binding.fullImage.post { holder.ensureBaseMatrix() }
        }
    }

    /**
     * Thumbnail strip (C-58). Item views are built in code — no new
     * layout file — as a MaterialCardView (48x72dp, 8dp radius) holding
     * a centerCrop ImageView, loaded from the same URLs (and therefore
     * the same ImageLoader disk/memory cache) as the main viewer.
     * Selected thumbnail: 2dp gold (colorTertiary = Phase 2
     * md_tertiary, the same "current page" ring S-04 uses in its
     * pager-order strip) stroke + full opacity; unselected: 1dp
     * colorOutline stroke + 55% opacity.
     */
    private inner class ThumbAdapter : RecyclerView.Adapter<ThumbAdapter.ThumbHolder>() {
        private var selectedPosition = RecyclerView.NO_POSITION

        fun setSelected(position: Int) {
            if (position == selectedPosition) return
            val old = selectedPosition
            selectedPosition = position
            if (old != RecyclerView.NO_POSITION) notifyItemChanged(old)
            if (position != RecyclerView.NO_POSITION) notifyItemChanged(position)
        }

        inner class ThumbHolder(val card: MaterialCardView, val image: ImageView) :
            RecyclerView.ViewHolder(card)

        override fun getItemCount(): Int = screenshots.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ThumbHolder {
            val context = parent.context
            val card = MaterialCardView(context).apply {
                layoutParams = RecyclerView.LayoutParams(dp(48), dp(72)).apply {
                    marginEnd = dp(8)
                }
                radius = dp(8).toFloat()
                cardElevation = 0f
                strokeWidth = dp(1)
                setCardBackgroundColor(
                    ContextCompat.getColor(context, R.color.colorSurface),
                )
            }
            val image = ImageView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            card.addView(image)
            return ThumbHolder(card, image)
        }

        override fun onBindViewHolder(holder: ThumbHolder, position: Int) {
            val url = screenshots[position]
            holder.image.tag = url
            ImageLoader.load(url, holder.image)
            val selected = position == selectedPosition
            holder.card.strokeWidth = dp(if (selected) 2 else 1)
            holder.card.strokeColor = ContextCompat.getColor(
                holder.card.context,
                if (selected) R.color.colorTertiary else R.color.colorOutline,
            )
            holder.image.alpha = if (selected) 1f else 0.55f
            holder.card.setOnClickListener { onThumbClicked(position) }
        }
    }
}
