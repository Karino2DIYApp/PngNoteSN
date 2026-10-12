package io.github.karino2.pngnote.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import io.github.karino2.pngnote.book.PageInfo
import io.github.karino2.supernote.EinkPixelWriter
import io.github.karino2.supernote.PenType
import java.util.Date
import kotlin.math.abs

@SuppressLint("ViewConstructor")
class CanvasSupernote(context: Context, private val bitmapBackend: UndoableBackend, var initialBmp: Bitmap? = null, private val background: Bitmap?, initialPageIdx:Int  = 0) : View(context) {

    val einkPW by lazy {
        EinkPixelWriter(this, ::onHardwareStrokeFinished)
    }

    private val pencilWidth = 4
    private val eraserWidth = 50

    // pencilと呼んでいるが本当はink、あとで直す
    private var isPencil = true
    private val isEraser : Boolean
        get() = !isPencil

    private val strokeWidth: Int
        get() = if(isPencil) pencilWidth else eraserWidth

    private val strokeColor: Int
        get() = if(isPencil) Color.BLACK else Color.WHITE


    private var shiftHalf = false

    private val bmpPaintWithBG = Paint(Paint.DITHER_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY) }

    private var undoCount = 0
    private var redoCount = 0

    fun undo(count : Int) {
        if (undoCount != count) {
            undoCount = count
            bitmapBackend.undo()
            refreshAfterUndoRedo()
        }
    }

    fun redo(count: Int) {
        if(redoCount != count) {
            redoCount = count
            bitmapBackend.redo()
            refreshAfterUndoRedo()
        }
    }

    fun onHardwareStrokeFinished(bitmap: Bitmap, dirty: Rect) {
        bitmapBackend.updateRegion(bitmap, dirty)
        // これが無いとrefreshした時にクリアされたバックエンドのバッファで上書きされてしまう。
        einkPW.clearTransient(dirty)
        // isBackendDirty = true
    }

    /*
    undoやredoのあとなどにも呼ばれる。
    たぶんbackendをeink側のvramに反映させる必要がある処理。
    ただ単にinvalidateでも良さそうな場所もあるので、あとで調べてrenameする。
     */
    private fun syncBackendToSurface() {
        bitmapBackend.withLock {
            einkPW.loadBitmap(bitmapBackend.bitmap)
        }
        invalidate() // 多分いらない。あとで消して確認
    }


    private fun refreshAfterUndoRedo() {
        bitmapBackend.notifyBitmapUpdate()
        bitmapBackend.notifyUndoStateChanged()
        syncBackendToSurface()
    }

    private var initCount = 0

    private var lastOpenedLimit: Rect? = null

    private fun visibleRect(): Rect {
        val limit = Rect()
        getLocalVisibleRect(limit)

        // supernoteでは以下はたぶんいらない。確認して削除
        // I don't know the reason, but this geometry seems to lower for 40px.
        // limit.offset(0, -40)

        return limit
    }

    private fun isRawRenderingBecomesStale(limit: Rect): Boolean =
        einkPW.isEnabled && (lastOpenedLimit == null || limit.width() != lastOpenedLimit?.width() || limit.height() != lastOpenedLimit?.height())


    private var _requestRawDrawingPending = false

    private fun applyRawDrawingSettings() {
        val limit = visibleRect()
        if (limit.width() <= 0 || limit.height() <= 0) return

        // サイズが変わっていたら、バッファ不整合を防ぐため開き直す（黒画面対策の核心）
        if (isRawRenderingBecomesStale(limit)) {
            ensureCloseRawRendering()
        }

        if (!einkPW.isEnabled) {
            einkPW.open()
            einkPW.setPen(PenType.INK, strokeWidth, strokeColor)
            einkPW.setWritableRect(limit)
            lastOpenedLimit = Rect(limit)

            bitmapBackend.withLock {
                einkPW.loadBitmap(bitmapBackend.bitmap)
            }
        } else {
            einkPW.setWritableRect(limit)
        }
    }

    fun ensureCloseRawRendering() {
        if (einkPW.isEnabled) {
            einkPW.isEnabled = false
        }
        _requestRawDrawingPending = false
    }

    fun requestRawDrawing() {
        if (width != 0 && height != 0)
        {
            applyRawDrawingSettings()
            _requestRawDrawingPending = false
        } else {
            _requestRawDrawingPending = true
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // タッチは無視。スタイラスオンリー
        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER)
            return super.onTouchEvent(event)

        einkPW.forwardEvent(event)
        return true
    }

    private val borderPaint = Paint().apply { color = Color.LTGRAY }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        bitmapBackend.resize(w, h)
        super.onSizeChanged(w, h, oldw, oldh)
        if (_requestRawDrawingPending)
        {
            _requestRawDrawingPending = false
            applyRawDrawingSettings()
        }
        initialBmp?.let {
            bitmapBackend.drawBitmap(it)
            initialBmp = null
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (!bitmapBackend.isReady)
            return

        bitmapBackend.withLock {
            canvas.drawBitmap(bitmapBackend.bitmap, 0f, 0f, null);
        }
        if(shiftHalf) {
            // 真ん中-2pxから2pxの太さのボーダーラインをborderPaintで描く
            canvas.drawRect(
                0f, (height / 2 - 2).toFloat(), width.toFloat(), (height / 2 + 2).toFloat(), borderPaint
            )
        }

    }

    fun ensureInit(callCount: Int) {
        if(callCount == 1 && initCount != 1) {
            initCount = 1
            applyRawDrawingSettings()
        }
    }

    // Supernoteでは特にfirst特有の処理は無いが、
    // BOOXとコードをあわせるためこのメソッドでraw drawingをenableする。
    fun firstInit() {
        requestDelaySync()
    }

    private var restartCount = 0

    fun onRestart(count: Int) {
        if (count != restartCount) {
            syncBackendToSurface()
            applyRawDrawingSettings()

            restartCount = count
        }
    }

    private var tryRawDrawingCount = 0

    fun onTryRawDrawing(count: Int) {
        if (count != tryRawDrawingCount) {
            applyRawDrawingSettings()
            tryRawDrawingCount = count
        }
    }


    private var ensureCloseCount = 0

    fun onEnsureClose(count: Int) {
        if (count != ensureCloseCount) {
            ensureCloseRawRendering()
            syncBackendToSurface()

            ensureCloseCount = count
        }
    }

    private fun pencil() {
        if (isPencil)
            return
        isPencil = true

        applyPenSettingChanged()
    }

    private fun eraser() {
        if (isEraser)
            return
        isPencil = false

        applyPenSettingChanged()
    }

    fun penOrEraser(isPen: Boolean ) {
        if(isPen == isPencil)
            return
        if (isPen) {
            pencil()
        } else {
            eraser()
        }
    }

    private fun applyPenSettingChanged() {
        einkPW.setPen(PenType.INK, strokeWidth, strokeColor)
        syncBackendToSurface()
    }

    private var refreshCount = 0
    fun refreshUI(count: Int) {
        if(refreshCount != count) {
            syncBackendToSurface()
            refreshCount = count
        }
    }

    fun withTempNoRawRendering(f: ()->Unit) {
        val isRawRendering = einkPW.isEnabled
        if (isRawRendering)
        {
            einkPW.close()
        }
        f()
        if (isRawRendering)
        {
            einkPW.open()
        }
    }

    // 暇な時にSurfaceViewを書き直す。
    private var lastSync = 0L
    private var lastRequest = 0L
    private val delayInterval = 2000L

    private fun getCurrentMills() = (Date()).time

    private fun requestDelaySync() {
        lastRequest = getCurrentMills()
        postDelayed({ maySyncBack() }, delayInterval)
    }

    // syncbackという名前はBOOXの名残
    // 単に暇な時にinvalidateするだけ。
    private fun maySyncBack() {
        // println("delay sync0")
        if (lastSync > lastRequest)
            return

        // 十分最近syncされてる。
        if( abs(lastSync - lastRequest) < 100L) {
            // println("delay sync1")
            requestDelaySync()
            return
        }
        lastSync = getCurrentMills()
        invalidate()
    }

    private var pageIdx = initialPageIdx

    fun onPageIdx(pageInfo: PageInfo, bitmapLoader: (PageInfo)->Bitmap?) {
        if(pageIdx == pageInfo.idx && pageInfo.shift == this.shiftHalf)
            return

        pageIdx = pageInfo.idx
        this.shiftHalf = pageInfo.shift

        val newbmp = bitmapLoader(pageInfo)
        bitmapBackend.setupNewPage(width, height, newbmp)

        syncBackendToSurface()
    }

    fun setOnUpdateListener(updateBmpListener: () -> Unit) {
        bitmapBackend.updateBmpListener = updateBmpListener
    }

    fun setOnUndoStateListener(undoStateListener: (undo:Boolean, redo:Boolean) -> Unit) {
        bitmapBackend.undoStateListener = undoStateListener
    }

    fun onFinish() {
        ensureCloseRawRendering()
    }



}