package com.gohar.store

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.LayoutInflater
import android.widget.Toast
import androidx.annotation.AttrRes
import androidx.annotation.StringRes
import com.gohar.store.databinding.ToastGoharBinding
import com.google.android.material.R as MaterialR

/**
 * The store's only toast channel (C-44 / D-5.2): a single static
 * Toast, cancelled before each show so toasts never stack, rendered
 * with the custom layout (res/layout/toast_gohar.xml) in three
 * variants by meaning:
 *
 * - SUCCESS: secondaryContainer bg + onSecondaryContainer text +
 *   secondary check disc.
 * - ERROR: errorContainer bg + onErrorContainer text + error "!" disc.
 * - INFO: surfaceContainerHigh bg + onSurface text +
 *   secondaryContainer "i" disc.
 *
 * Colours resolve from the theme attributes at show time, so the
 * same call renders correctly in both themes and both locales. The
 * message view gets Nastaleeq in the Urdu locale (C-47); the icon
 * disc deliberately does not — its glyphs are Latin.
 *
 * Durations match the old Toast.LENGTH_SHORT / LONG (~2s / ~3.5s).
 * No success toast is ever invented for download-complete or
 * editor-save (D-5.5 — the state change is the feedback).
 */
object ToastHelper {

    enum class Type { SUCCESS, ERROR, INFO }

    private var current: Toast? = null

    fun show(context: Context, type: Type, message: CharSequence, long: Boolean = false) {
        showResolved(context, type, message.toString(), long)
    }

    fun show(context: Context, type: Type, @StringRes resId: Int, long: Boolean = false) {
        showResolved(context, type, context.getString(resId), long)
    }

    private fun showResolved(context: Context, type: Type, message: String, long: Boolean) {
        // Resolve the colours from the caller's (activity) theme, then
        // build the Toast on the application context so the static
        // reference never holds an Activity.
        val colors = colorsFor(context, type)
        val appContext = context.applicationContext
        val binding = ToastGoharBinding.inflate(LayoutInflater.from(appContext))
        val density = appContext.resources.displayMetrics.density
        binding.toastRoot.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12f * density
            setColor(colors.background)
        }
        binding.toastIcon.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(colors.disc)
        }
        binding.toastIcon.text = when (type) {
            Type.SUCCESS -> "✓"
            Type.ERROR -> "!"
            Type.INFO -> "i"
        }
        binding.toastIcon.setTextColor(colors.discText)
        binding.toastMessage.text = message
        binding.toastMessage.setTextColor(colors.text)
        // Inflated in code, so the theme's fontFamily does not apply —
        // Urdu messages need Nastaleeq explicitly (C-47).
        applyUrduFontIfNeeded(binding.toastMessage)
        current?.cancel()
        current = Toast(appContext).apply {
            view = binding.root
            duration = if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        }
        current?.show()
    }

    private data class ToastColors(
        val background: Int,
        val text: Int,
        val disc: Int,
        val discText: Int,
    )

    private fun colorsFor(context: Context, type: Type): ToastColors {
        return when (type) {
            Type.SUCCESS -> ToastColors(
                background = resolve(context, MaterialR.attr.colorSecondaryContainer),
                text = resolve(context, MaterialR.attr.colorOnSecondaryContainer),
                disc = resolve(context, MaterialR.attr.colorSecondary),
                discText = resolve(context, MaterialR.attr.colorOnSecondary),
            )
            Type.ERROR -> ToastColors(
                background = resolve(context, MaterialR.attr.colorErrorContainer),
                text = resolve(context, MaterialR.attr.colorOnErrorContainer),
                disc = resolve(context, MaterialR.attr.colorError),
                discText = resolve(context, MaterialR.attr.colorOnError),
            )
            Type.INFO -> ToastColors(
                background = resolve(context, MaterialR.attr.colorSurfaceContainerHigh),
                text = resolve(context, MaterialR.attr.colorOnSurface),
                disc = resolve(context, MaterialR.attr.colorSecondaryContainer),
                discText = resolve(context, MaterialR.attr.colorOnSecondaryContainer),
            )
        }
    }

    /** Resolves a theme colour attribute to its ARGB int. */
    private fun resolve(context: Context, @AttrRes attr: Int): Int {
        val value = TypedValue()
        return if (context.theme.resolveAttribute(attr, value, true)) {
            value.data
        } else {
            Color.TRANSPARENT
        }
    }
}
