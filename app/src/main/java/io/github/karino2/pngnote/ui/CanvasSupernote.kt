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
import kotlin.math.max
import kotlin.math.min

@SuppressLint("ViewConstructor")
class CanvasSupernote(context: Context, private val backend: UndoableBackend, var initialBmp: Bitmap? = null, private val background: Bitmap?, initialPageIdx:Int  = 0) : View(context) {

    val einkPW by lazy {
        EinkPixelWriter(this, ::onHardwareStrokeFinished)
    }

    private val pencilWidth = 8f
    private val eraserWidth = 120f

    // pencilと呼んでいるが本当はink、あとで直す
    private var isPencil = true
    private val isEraser : Boolean
        get() = !isPencil

    private val strokeWidth: Float
        get() = if(isPencil) pencilWidth else eraserWidth

    private val strokeColor: Int
        get() = if(isPencil) Color.BLACK else Color.WHITE


    private var shiftHalf = false

    private val bmpPaintWithBG = Paint(Paint.DITHER_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY) }

    fun undo() {
        backend.undo()
        refreshAfterUndoRedo()
    }

    fun redo() {
        backend.redo()
        refreshAfterUndoRedo()
    }

    fun boundTo(rect: Rect, target: Bitmap) : Rect {
        val left = max(0, rect.left)
        val top = max(0, rect.top)
        val right = min(target.width, rect.right)
        val bottom = min(target.height, rect.bottom)
        return Rect(left, top, right, bottom)
    }

    fun onHardwareStrokeFinished(bitmap: Bitmap, dirty: Rect) {
        // dirty.topは外まで行く場合があり、その場合マイナスになる。
        val hostDirty = backend.withLock { boundTo(dirty, backend.bitmap) }
        backend.updateRegion(bitmap, hostDirty)
        // これが無いとrefreshした時にクリアされたバックエンドのバッファで上書きされてしまう。
        einkPW.clearTransient(dirty)
    }

    /*
    undoやredoのあとなどにも呼ばれる。
    たぶんbackendをeink側のvramに反映させる必要がある処理。
    ただ単にinvalidateでも良さそうな場所もあるので、あとで調べてrenameする。
     */
    private fun syncBackendToSurface() {
        if(!backend.isReady)
            return
        backend.withLock {
            einkPW.loadBitmap(backend.bitmap)
        }
        invalidate() // 多分いらない。あとで消して確認
    }


    private fun refreshAfterUndoRedo() {
        syncBackendToSurface()
    }

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

    fun setPen(pwidth: Float, pcolor: Int) {
        einkPW.setPen(PenType.INK, pwidth, pcolor)
    }

    fun applyRawDrawingSettings() {
        val limit = visibleRect()
        if (limit.width() <= 0 || limit.height() <= 0) return

        // サイズが変わっていたら、バッファ不整合を防ぐため開き直す（黒画面対策の核心）
        if (isRawRenderingBecomesStale(limit)) {
            ensureCloseRawRendering()
        }

        if (!einkPW.isEnabled) {
            einkPW.open()
            setPen( strokeWidth, strokeColor)
            einkPW.setWritableRect(limit)
            lastOpenedLimit = Rect(limit)

            backend.withLock {
                einkPW.loadBitmap(backend.bitmap)
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
        backend.resize(w, h)
        super.onSizeChanged(w, h, oldw, oldh)
        if (_requestRawDrawingPending)
        {
            _requestRawDrawingPending = false
            applyRawDrawingSettings()
        }
        initialBmp?.let {
            backend.setupNewBitmap(it)
            initialBmp = null
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (!backend.isReady)
            return

        backend.withLock {
            canvas.drawBitmap(backend.bitmap, 0f, 0f, null);
        }
        if(shiftHalf) {
            // 真ん中-2pxから2pxの太さのボーダーラインをborderPaintで描く
            canvas.drawRect(
                0f, (height / 2 - 2).toFloat(), width.toFloat(), (height / 2 + 2).toFloat(), borderPaint
            )
        }

    }

    // Supernoteでは特にfirst特有の処理は無いが、
    // BOOXとコードをあわせるためこのメソッドでraw drawingをenableする。
    fun firstInit() {
        requestDelaySync()
    }

    fun onRestart() {
        syncBackendToSurface()
        applyRawDrawingSettings()
    }

    fun closeRawRendering() {
        ensureCloseRawRendering()
        syncBackendToSurface()
    }

    fun pencil() {
        if (isPencil)
            return
        isPencil = true

        applyPenSettingChanged()
    }

    fun eraser() {
        if (isEraser)
            return
        isPencil = false

        applyPenSettingChanged()
    }

    private fun applyPenSettingChanged() {
        setPen(strokeWidth, strokeColor)
        syncBackendToSurface()
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



    private var _pageIdx = -1
    fun onPageIdx(pageInfo: PageInfo) {
        if(pageInfo.idx == _pageIdx && pageInfo.shift == shiftHalf)
            return

        _pageIdx = pageInfo.idx
        this.shiftHalf = pageInfo.shift

        syncBackendToSurface()
    }

    fun onFinish() {
        ensureCloseRawRendering()
    }



}