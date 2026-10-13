package io.github.karino2.pngnote.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import kotlin.concurrent.withLock

class UndoableBackend {

    private var bmpbackendInitialized = false
    lateinit var bmpBackend : io.github.karino2.supernote.BitmapBackend

    val width : Int
        get() = bmpBackend.width

    val height: Int
        get() = bmpBackend.height

    val isReady: Boolean
        get() =  bmpbackendInitialized

    private val undoList = UndoList()

    val bitmap: Bitmap
        get() = bmpBackend.bitmap

    var updateBmpListener: () -> Unit = {}
    var undoStateListener: (undo: Boolean, redo: Boolean) -> Unit = { _, _ -> }

    inline fun <T> withLock(block: () -> T): T = bmpBackend.bitmapLock.withLock(block)


    fun notifyBitmapUpdate() {
        updateBmpListener()
    }

    fun notifyUndoStateChanged() {
        undoStateListener(canUndo, canRedo)
    }

    val canUndo: Boolean
        get() = undoList.canUndo

    val canRedo: Boolean
        get() = undoList.canRedo

    fun clearUndo() {
        undoList.clear()
    }

    fun pushUndoCommand(x: Int, y: Int, undoBmp: Bitmap, redoBmp: Bitmap) {
        undoList.pushUndoCommand(x, y, undoBmp, redoBmp)
    }

    fun undo() {
        bmpBackend.withLock {
            undoList.undo(bmpBackend.canvas)
        }
    }

    fun redo() {
        bmpBackend.withLock {
            undoList.redo(bmpBackend.canvas)
        }
    }

    fun updateRegion(newBmp: Bitmap, region: Rect) {
        val (undo, redo) = bmpBackend.withLock {
            val undo = Bitmap.createBitmap(
                bitmap,
                region.left,
                region.top,
                region.width(),
                region.height()
            )
            bmpBackend.drawBitmapNL(newBmp, region, region)
            val redo = Bitmap.createBitmap(
                bitmap,
                region.left,
                region.top,
                region.width(),
                region.height()
            )
            Pair(undo, redo)
        }
        pushUndoCommand(region.left, region.top, undo, redo)

        notifyBitmapUpdate()
        notifyUndoStateChanged()
        /*
        bmpBackend.drawBitmap(newBmp, region, region)
        notifyBitmapUpdate()
         */
    }


    fun ensureSize(width: Int, height: Int): Pair<Bitmap, Canvas> {
        if (!bmpbackendInitialized) {
            bmpBackend = io.github.karino2.supernote.BitmapBackend(width, height)
            bmpbackendInitialized = true
        } else {
            bmpBackend.resize(width, height)
        }
        return Pair(bitmap, bmpBackend.canvas)
    }

    fun cleanInit(width: Int, height: Int, initialBmp: Bitmap?) {
        initialBmp?.let {
            bmpBackend.drawBitmap(it,
                Rect(0, 0, it.width, it.height),
                Rect(0, 0, width, height))
        }
        notifyBitmapUpdate()
    }

    fun drawBitmap(newBmp: Bitmap) {
        if (!bmpbackendInitialized)
            ensureSize(newBmp.width, newBmp.height)

        bmpBackend.drawBitmap(newBmp)
    }
    fun clear() = bmpBackend.clear()
    fun resize(width: Int, height: Int) = ensureSize(width, height)

    fun setupNewPage(width: Int, height: Int, newBmp: Bitmap?) {
        val (offscreenBmp, canvas) = ensureSize(width, height)
        bmpBackend.clear()
        newBmp?.let {
            bmpBackend.drawBitmap(it,
                Rect(0, 0, it.width, it.height),
                Rect(0, 0, width, height))
        }
        clearUndo()
        notifyUndoStateChanged()
        notifyBitmapUpdate()
    }
}