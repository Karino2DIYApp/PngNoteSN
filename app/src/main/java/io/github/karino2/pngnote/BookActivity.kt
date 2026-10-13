package io.github.karino2.pngnote

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import io.github.karino2.fastfile.FastFile
import io.github.karino2.pngnote.book.Book
import io.github.karino2.pngnote.book.BookIO
import io.github.karino2.pngnote.book.PageInfo
import io.github.karino2.pngnote.ui.CanvasSupernote
import io.github.karino2.pngnote.ui.UndoableBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date
import kotlin.time.Duration.Companion.milliseconds

class BookActivity : AppCompatActivity() {
    private lateinit var dirUrl: Uri
    private var initialPageIdx = 0

    private val bookDir by lazy {
        FastFile.fromTreeUri(this, dirUrl)
    }

    private val bookIO by lazy { BookIO(contentResolver) }

    private var _book: Book? = null
    set(newbook) {
        field = newbook
        pageNum = newbook?.pageNum ?: 0
        updateUI()
    }

    private val book: Book
    get() {
        _book?.let { return it }
        bookIO.loadBook(bookDir).also {
            _book = it
            return it
        }
    }

    private fun showMessage(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private var initCount = 0
    private var pageInfoState = PageInfo(initialPageIdx, false)
    private var pageNum = 0
    private var restartCount = 0
    private var closeCount = 0
    private var tryRawDrawing = 0
    private var canRedo = false
    private var undoCount = 0
    private var redoCount = 0
    private var refreshCount = 0

    private var lastWritten = -1L
    private var emptyBmp: Bitmap? = null
    private var isDirty = false
    private var lastUpdatedPageInfo = pageInfoState.copy()

    private val backend = UndoableBackend()
    private lateinit var canvasSupernote: CanvasSupernote

    // UI Views
    private lateinit var btnPen: ImageButton
    private lateinit var btnEraser: ImageButton
    private lateinit var btnUndo: ImageButton
    private lateinit var btnRedo: ImageButton
    private lateinit var btnShiftHalf: ImageButton
    private lateinit var btnGrid: ImageButton
    private lateinit var btnFirstPage: ImageButton
    private lateinit var btnPrevPage: ImageButton
    private lateinit var tvPageNum: TextView
    private lateinit var btnNextPage: ImageButton
    private lateinit var btnLastPage: ImageButton
    private lateinit var btnAddPage: ImageButton
    private lateinit var btnShare: ImageButton

    private var isEraser = false

    private fun notifyBitmapUpdate() {
        isDirty = true
        lastWritten = getCurrentMills()
        lastUpdatedPageInfo = pageInfoState.copy()
        lazySave()
    }

    private var canUndo = false
    private fun notifyUndoStateChanged(canUndo1: Boolean, canRedo1: Boolean) {
        val needRefresh = (canRedo != canRedo1)
        canUndo = canUndo1
        canRedo = canRedo1
        if (needRefresh) {
            refreshCount++
        }
        updateUI()
    }

    private fun getCurrentMills() = Date().time

    private suspend fun savePageData(pageInfo: PageInfo, pageBmp: Bitmap?) {
        bookIO.saveBitmap(book, pageInfo.idx, pageInfo.shift, pageBmp!!)
        withContext(Dispatchers.Main) {
            _book = book.assignNonEmpty(pageInfo.idx)
        }
    }

    private inline fun <T> withLock(block: () -> T): T? = backend.withLock(block)

    private fun saveBackendToPageInMain(pageInfo: PageInfo) {
        withLock {
            saveBitmapAsPageInMain(pageInfo.idx, pageInfo.shift, backend.bitmap)
        }
    }

    private fun saveBitmapAsPageInMain(pageIdx: Int, shift: Boolean, pageBmp: Bitmap) {
        bookIO.saveBitmap(book, pageIdx, shift, pageBmp)
        _book = book.assignNonEmpty(pageIdx)
    }

    private val SAVE_INTERVAL_MILL = 5000L
    private fun lazySave() {
        lifecycleScope.launch(Dispatchers.IO) {
            delay(SAVE_INTERVAL_MILL.milliseconds)
            if (isDirty && (getCurrentMills() - lastWritten) >= SAVE_INTERVAL_MILL) {
                val (pinfoSnapshot, bmpSnapshot) = withContext(Dispatchers.Main) {
                    if (!isDirty || lastUpdatedPageInfo != pageInfoState)
                        return@withContext Pair(PageInfo(-1, false), null)

                    isDirty = false
                    withLock {
                        val bmp = backend.bitmap
                        Pair(lastUpdatedPageInfo, bmp.copy(bmp.config!!, false))
                    }!!
                }
                if (pinfoSnapshot.idx != -1)
                    savePageData(pinfoSnapshot, bmpSnapshot)
            }
        }
    }

    private fun ensureSave() {
        if (isDirty) {
            isDirty = false
            saveBackendToPageInMain(pageInfoState)
        }
    }

    private val handler = Handler()

    override fun onResume() {
        super.onResume()
        tryRawDrawing++
        updateUI()
    }

    override fun onRestart() {
        super.onRestart()
        restartCount++
        updateUI()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            restartCount++
            updateUI()
        }
    }

    override fun onStop() {
        closeCount++
        ensureSave()
        super.onStop()
    }

    private fun share() {
        closeCount++
        ensureSave()
        if (!backend.isReady) {
            return
        }
        val path = File.createTempFile("share", ".png", cacheDir)
        path.outputStream().use {
            withLock {
                backend.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            it.flush()
        }
        val u = FileProvider.getUriForFile(this, applicationContext.packageName + ".provider", path)
        shareImageUri(u)
    }

    private fun shareImageUri(uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND)
        intent.putExtra(Intent.EXTRA_STREAM, uri)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.type = "image/png"
        startActivity(intent)
    }

    private fun addNewPageAndGo() {
        ensureSave()
        if (emptyBmp == null) {
            emptyBmp = Bitmap.createBitmap(canvasSupernote.width, canvasSupernote.height, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.WHITE)
            }
        }

        _book = book.addPage()
        val newPageIdx = pageNum - 1
        pageInfoState = pageInfoState.copy(idx = newPageIdx)

        emptyBmp?.let { ebmp ->
            saveBitmapAsPageInMain(newPageIdx, false, ebmp)
        }
        updateUI()
    }

    private fun gotoFirstPage() {
        ensureSave()
        pageInfoState = PageInfo(0, false)
        updateUI()
    }

    private fun gotoLastPage() {
        ensureSave()
        pageInfoState = PageInfo(pageNum - 1, false)
        updateUI()
    }

    private fun gotoPrevPage() {
        ensureSave()
        if (pageInfoState.idx >= 1) {
            pageInfoState = pageInfoState.copy(idx = pageInfoState.idx - 1)
            updateUI()
        }
    }

    private fun gotoNextPage() {
        ensureSave()
        val newPageIdx = pageInfoState.idx + 1
        val shiftHalf = if (newPageIdx + 1 == pageNum) { false } else { pageInfoState.shift }
        pageInfoState = pageInfoState.copy(idx = newPageIdx, shift = shiftHalf)
        updateUI()
    }

    private fun gotoGridPage() {
        Intent(this, PageGridActivity::class.java).also {
            it.data = dirUrl
            startActivity(it)
        }
    }

    var finishListener: () -> Unit = {}

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePageIdxArg(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        intent?.let {
            dirUrl = it.data!!
            handlePageIdxArg(it)
        }

        setContentView(R.layout.activity_book)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener {
            finishListener()
            ensureSave()
            finish()
        }

        btnPen = findViewById(R.id.btnPen)
        btnEraser = findViewById(R.id.btnEraser)
        btnUndo = findViewById(R.id.btnUndo)
        btnRedo = findViewById(R.id.btnRedo)
        btnShiftHalf = findViewById(R.id.btnShiftHalf)
        btnGrid = findViewById(R.id.btnGrid)
        btnFirstPage = findViewById(R.id.btnFirstPage)
        btnPrevPage = findViewById(R.id.btnPrevPage)
        tvPageNum = findViewById(R.id.tvPageNum)
        btnNextPage = findViewById(R.id.btnNextPage)
        btnLastPage = findViewById(R.id.btnLastPage)
        btnAddPage = findViewById(R.id.btnAddPage)
        btnShare = findViewById(R.id.btnShare)

        btnPen.setOnClickListener {
            isEraser = false
            updateUI()
        }

        btnEraser.setOnClickListener {
            isEraser = true
            updateUI()
        }

        btnUndo.setOnClickListener {
            if (canUndo) {
                undoCount++
                updateUI()
            } else {
                showMessage("Not yet undo-able.")
            }
        }

        btnRedo.setOnClickListener {
            if (canRedo) {
                redoCount++
                updateUI()
            }
        }

        btnShiftHalf.setOnClickListener {
            ensureSave()
            pageInfoState = pageInfoState.copy(shift = !pageInfoState.shift)
            updateUI()
        }

        btnGrid.setOnClickListener {
            gotoGridPage()
        }

        btnFirstPage.setOnClickListener {
            gotoFirstPage()
        }

        btnPrevPage.setOnClickListener {
            gotoPrevPage()
        }

        btnNextPage.setOnClickListener {
            gotoNextPage()
        }

        btnLastPage.setOnClickListener {
            gotoLastPage()
        }

        btnAddPage.setOnClickListener {
            addNewPageAndGo()
        }

        btnShare.setOnClickListener {
            share()
        }

        val canvasContainer = findViewById<FrameLayout>(R.id.canvasContainer)
        val initBmp = bookIO.loadBitmapOrNull(book, pageInfoState)
        val bgBmp = bookIO.loadBgOrNull(book)
        canvasSupernote = CanvasSupernote(this, backend, initBmp, bgBmp, initialPageIdx).apply {
            clipToOutline = true
            firstInit()
            setOnUpdateListener { notifyBitmapUpdate() }
            setOnUndoStateListener { undo, redo -> notifyUndoStateChanged(undo, redo) }
            finishListener = { onFinish() }
        }
        canvasContainer.addView(canvasSupernote)

        handler.post {
            initCount = 1
            updateUI()
        }
    }

    private fun updateUI() {
        if (!::canvasSupernote.isInitialized) return

        btnPen.alpha = if (isEraser) 0.3f else 1.0f
        btnEraser.setImageResource(if (isEraser) R.mipmap.eraser_button_selected else R.mipmap.eraser_button)

        btnRedo.isEnabled = canRedo

        btnShiftHalf.setImageResource(if (pageInfoState.shift) R.drawable.baseline_shift_half else R.drawable.outline_shift_half)

        val pidx = pageInfoState.idx + 1
        val pnum = pageNum
        if (pageInfoState.shift) {
            tvPageNum.text = "$pidx.5/$pnum"
        } else {
            tvPageNum.text = "$pidx/$pnum"
        }

        val isFirst = pageInfoState.isFirstPage
        btnFirstPage.isEnabled = !isFirst
        btnPrevPage.isEnabled = !isFirst

        val lastPage = pidx == pnum
        btnNextPage.isEnabled = !lastPage
        btnLastPage.isEnabled = !lastPage

        canvasSupernote.apply {
            ensureInit(initCount)
            penOrEraser(!isEraser)
            onPageIdx(pageInfoState, bitmapLoader = { pinfo ->
                bookIO.loadBitmapOrNull(book, pinfo).also { bmp ->
                    this@BookActivity.isDirty = false
                    if (bmp == null) {
                        backend.clear()
                    } else {
                        backend.drawBitmap(bmp)
                    }
                }
            })
            onRestart(restartCount)
            onEnsureClose(closeCount)
            onTryRawDrawing(tryRawDrawing)
            undo(undoCount)
            redo(redoCount)
            refreshUI(refreshCount)
        }
    }

    private fun handlePageIdxArg(intent: Intent) {
        val argPageIdx = intent.getIntExtra("PAGE_IDX", -1)
        if (argPageIdx != -1) {
            initialPageIdx = argPageIdx
            pageInfoState = pageInfoState.copy(idx = initialPageIdx)
        }
    }
}
