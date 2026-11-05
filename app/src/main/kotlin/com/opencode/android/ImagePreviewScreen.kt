package com.opencode.android

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.util.concurrent.Executors

/**
 * Minimal image preview.
 *
 * The bitmap is sampled to the screen size before decoding, so opening a multi-megapixel photo
 * from a repository does not allocate its full ARGB buffer. Every other format keeps the external
 * viewer as a fallback from the overflow menu.
 */
class ImagePreviewScreen(
    private val activity: Activity,
    private val listener: Listener,
) {

    interface Listener {
        fun onImageClosed()
    }

    private val panel: View = activity.findViewById(R.id.image_panel)
    private val toolbar: MaterialToolbar = activity.findViewById(R.id.image_toolbar)
    private val image: ImageView = activity.findViewById(R.id.image_view)
    private val progress: ProgressBar = activity.findViewById(R.id.image_progress)
    private val error: TextView = activity.findViewById(R.id.image_error)
    private val meta: TextView = activity.findViewById(R.id.image_meta)

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "opencode-image").apply { isDaemon = true }
    }

    private var file: File? = null
    private var bitmap: Bitmap? = null
    private var generation = 0

    @Volatile
    private var released = false

    val isVisible: Boolean
        get() = panel.visibility == View.VISIBLE

    init {
        toolbar.setNavigationOnClickListener { goBack() }
        toolbar.setOnMenuItemClickListener(::onMenuItem)
    }

    fun show(target: File) {
        if (!target.isFile) {
            snack(activity.getString(R.string.opencode_image_failed))
            return
        }
        OverlayMotion.reveal(panel)
        file = target
        toolbar.title = target.name
        toolbar.subtitle = target.parentFile?.absolutePath.orEmpty()
        error.visibility = View.GONE
        meta.text = ""
        image.setImageDrawable(null)
        bitmap?.recycle()
        bitmap = null
        progress.visibility = View.VISIBLE

        val request = ++generation
        executor.execute {
            val decoded = decode(target)
            val bounds = dimensions(target)
            activity.runOnUiThread {
                if (released || request != generation) {
                    decoded?.recycle()
                    return@runOnUiThread
                }
                if (activity.isFinishing || activity.isDestroyed) {
                    decoded?.recycle()
                    return@runOnUiThread
                }
                progress.visibility = View.GONE
                if (decoded == null) {
                    image.visibility = View.GONE
                    error.visibility = View.VISIBLE
                    meta.text = OpenCodeStorage.formatBytes(target.length())
                    return@runOnUiThread
                }
                bitmap = decoded
                image.visibility = View.VISIBLE
                image.setImageBitmap(decoded)
                error.visibility = View.GONE
                val size = OpenCodeStorage.formatBytes(target.length())
                meta.text = if (bounds != null) {
                    activity.getString(
                        R.string.opencode_image_meta,
                        "${bounds.first}×${bounds.second}",
                        size,
                    )
                } else {
                    size
                }
            }
        }
    }

    fun goBack() {
        close()
    }

    fun release() {
        released = true
        generation++
        executor.shutdownNow()
        image.setImageDrawable(null)
        bitmap?.recycle()
        bitmap = null
    }

    private fun close() {
        generation++
        image.setImageDrawable(null)
        bitmap?.recycle()
        bitmap = null
        file = null
        progress.visibility = View.GONE
        listener.onImageClosed()
        OverlayMotion.conceal(panel)
    }

    private fun onMenuItem(item: MenuItem): Boolean {
        val target = file ?: return false
        when (item.itemId) {
            R.id.action_image_external -> {
                if (!FileActions.openExternal(activity, target)) {
                    snack(activity.getString(R.string.opencode_files_open_failed))
                }
            }
            R.id.action_image_properties -> FileActions.showProperties(activity, target)
            else -> return false
        }
        return true
    }

    private fun decode(target: File): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(target.absolutePath, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        val limit = maxOf(
            activity.resources.displayMetrics.widthPixels,
            activity.resources.displayMetrics.heightPixels,
            MIN_DECODE_EDGE,
        )
        var sample = 1
        while (longest / sample > limit * 2) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeFile(target.absolutePath, options)
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    private fun dimensions(target: File): Pair<Int, Int>? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(target.absolutePath, bounds)
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            bounds.outWidth to bounds.outHeight
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }

    private fun snack(message: String) {
        Snackbar.make(panel, message, Snackbar.LENGTH_LONG).show()
    }

    private companion object {
        const val MIN_DECODE_EDGE = 1024
    }
}
