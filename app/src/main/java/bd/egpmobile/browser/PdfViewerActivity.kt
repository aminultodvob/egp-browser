package bd.egpmobile.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import android.widget.ImageView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import bd.egpmobile.browser.databinding.ActivityPdfViewerBinding
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Built-in PDF viewer (android.graphics.pdf.PdfRenderer), so downloaded PDFs always open, even on
 * phones without a PDF app. Pages render on one background thread (PdfRenderer is not thread-safe)
 * and are cached by memory size. Pinch or double-tap to zoom; scroll sideways when zoomed.
 */
class PdfViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPdfViewerBinding
    private lateinit var uri: Uri
    private var mimeName = ""
    private val renderThread = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    // Accessed only on renderThread.
    private var descriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null

    /** Page width / height ratios, read once when the document opens. */
    private var pageRatios: List<Float> = emptyList()
    private var zoomIndex = 0
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 6).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val adapter = PageAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityPdfViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { root, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            root.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        uri = intent.data ?: return finish()
        mimeName = intent.getStringExtra(EXTRA_NAME).orEmpty()

        binding.toolbar.title = mimeName.ifEmpty { getString(R.string.pdf_viewer_title) }
        binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        binding.toolbar.setNavigationContentDescription(R.string.action_navigate_up)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.inflateMenu(R.menu.menu_pdf_viewer)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_zoom_in -> setZoom(zoomIndex + 1)
                R.id.action_zoom_out -> setZoom(zoomIndex - 1)
                R.id.action_open_with -> DocumentOpener.openWithOtherApp(this, uri, PDF_MIME, mimeName)
                R.id.action_share -> DocumentOpener.share(this, uri, PDF_MIME)
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        binding.errorOpenWith.setOnClickListener { DocumentOpener.openWithOtherApp(this, uri, PDF_MIME, mimeName) }

        binding.pages.layoutManager = LinearLayoutManager(this)
        binding.pages.adapter = adapter
        binding.pages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = updateIndicator()
        })
        binding.horizontal.post { applyZoomWidth() }
        openDocument()
    }

    override fun onDestroy() {
        renderThread.execute {
            runCatching { renderer?.close() }
            runCatching { descriptor?.close() }
        }
        renderThread.shutdown()
        cache.evictAll()
        super.onDestroy()
    }

    // region Zoom gestures

    private val scaleDetector by lazy {
        ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            private var accumulated = 1f
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                accumulated = 1f
                return true
            }
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                accumulated *= detector.scaleFactor
                return true
            }
            override fun onScaleEnd(detector: ScaleGestureDetector) {
                when {
                    accumulated > 1.15f -> setZoom(zoomIndex + 1)
                    accumulated < 0.87f -> setZoom(zoomIndex - 1)
                }
            }
        })
    }

    private val tapDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                setZoom(if (zoomIndex == 0) 2 else 0)
                return true
            }
        })
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (binding.horizontal.isVisible) {
            scaleDetector.onTouchEvent(event)
            tapDetector.onTouchEvent(event)
            if (scaleDetector.isInProgress) return true
        }
        return super.dispatchTouchEvent(event)
    }

    private fun setZoom(index: Int) {
        val clamped = index.coerceIn(0, ZOOM_LEVELS.lastIndex)
        if (clamped == zoomIndex) return
        val first = (binding.pages.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
        zoomIndex = clamped
        applyZoomWidth()
        if (first >= 0) binding.pages.scrollToPosition(first)
    }

    /** Zoom widens the page column; the outer HorizontalScrollView pans across it. */
    @SuppressLint("NotifyDataSetChanged") // Every page changes size.
    private fun applyZoomWidth() {
        val viewport = binding.horizontal.width
        if (viewport == 0) return
        binding.pages.layoutParams = binding.pages.layoutParams.apply { width = (viewport * ZOOM_LEVELS[zoomIndex]).toInt() }
        adapter.notifyDataSetChanged()
        binding.toolbar.menu.findItem(R.id.action_zoom_in)?.isEnabled = zoomIndex < ZOOM_LEVELS.lastIndex
        binding.toolbar.menu.findItem(R.id.action_zoom_out)?.isEnabled = zoomIndex > 0
    }

    // endregion

    @SuppressLint("NotifyDataSetChanged") // The whole page list is set at once.
    private fun openDocument() {
        binding.loading.isVisible = true
        renderThread.execute {
            val result = try {
                val fd = contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("No descriptor")
                val pdf = PdfRenderer(fd)
                descriptor = fd
                renderer = pdf
                val ratios = (0 until pdf.pageCount).map { i ->
                    pdf.openPage(i).use { page -> page.width.toFloat() / page.height.coerceAtLeast(1) }
                }
                Result.success(ratios)
            } catch (e: SecurityException) {
                Result.failure(e)
            } catch (e: IOException) {
                Result.failure(e)
            } catch (e: RuntimeException) {
                Result.failure(e)
            }
            main.post {
                if (isDestroyed) return@post
                binding.loading.isVisible = false
                result.onSuccess { ratios ->
                    if (ratios.isEmpty()) {
                        showError(getString(R.string.pdf_error_empty))
                    } else {
                        pageRatios = ratios
                        adapter.notifyDataSetChanged()
                        updateIndicator()
                    }
                }.onFailure { error ->
                    showError(
                        getString(if (error is SecurityException) R.string.pdf_error_protected else R.string.pdf_error_corrupt)
                    )
                }
            }
        }
    }

    private fun showError(message: String) {
        binding.horizontal.isVisible = false
        binding.pageIndicator.isVisible = false
        binding.errorMessage.text = message
        binding.errorView.isVisible = true
    }

    private fun updateIndicator() {
        val count = pageRatios.size
        if (count == 0) return
        val first = (binding.pages.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition().coerceAtLeast(0)
        binding.pageIndicator.text = getString(R.string.pdf_page_indicator, first + 1, count)
        binding.pageIndicator.isVisible = true
    }

    private inner class PageAdapter : RecyclerView.Adapter<PageAdapter.Holder>() {

        inner class Holder(val image: ImageView) : RecyclerView.ViewHolder(image) {
            var boundKey: String? = null
        }

        override fun getItemCount() = pageRatios.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_pdf_page, parent, false) as ImageView)

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val width = binding.pages.width.takeIf { it > 0 } ?: binding.horizontal.width
            if (width <= 0) return
            val height = (width / pageRatios[position]).toInt()
            holder.image.layoutParams = holder.image.layoutParams.apply { this.height = height }
            holder.image.contentDescription = getString(R.string.pdf_page_description, position + 1, pageRatios.size)
            val renderWidth = minOf(width, MAX_RENDER_WIDTH)
            val key = "$position@$renderWidth"
            holder.boundKey = key
            val cached = cache.get(key)
            if (cached != null) {
                holder.image.setImageBitmap(cached)
                return
            }
            holder.image.setImageDrawable(null)
            val renderHeight = (renderWidth / pageRatios[position]).toInt().coerceAtLeast(1)
            renderThread.execute {
                val pdf = renderer ?: return@execute
                val bitmap = try {
                    Bitmap.createBitmap(renderWidth, renderHeight, Bitmap.Config.ARGB_8888).also { bmp ->
                        bmp.eraseColor(Color.WHITE)
                        pdf.openPage(position).use { page -> page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                    }
                } catch (e: RuntimeException) {
                    null
                } catch (e: OutOfMemoryError) {
                    cache.evictAll()
                    null
                } ?: return@execute
                cache.put(key, bitmap)
                main.post { if (holder.boundKey == key) holder.image.setImageBitmap(bitmap) }
            }
        }

        override fun onViewRecycled(holder: Holder) {
            holder.boundKey = null
            holder.image.setImageDrawable(null)
        }
    }

    companion object {
        private const val EXTRA_NAME = "name"
        private const val PDF_MIME = "application/pdf"
        private val ZOOM_LEVELS = floatArrayOf(1f, 1.5f, 2f, 3f)
        /** Bitmaps wider than this are scaled up on screen instead, to bound memory use. */
        private const val MAX_RENDER_WIDTH = 2048

        fun intent(context: Context, uri: Uri, name: String): Intent =
            Intent(context, PdfViewerActivity::class.java)
                .setData(uri)
                .putExtra(EXTRA_NAME, name)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
