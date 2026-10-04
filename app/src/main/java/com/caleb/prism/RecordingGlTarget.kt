package com.caleb.prism

import android.opengl.EGL14
import android.opengl.EGLExt

/** Used only on GLSurfaceView's GL thread, sharing its current context and shader state. */
internal class RecordingGlTarget(private val encoder: SessionEncoder) {
    private val display = EGL14.eglGetCurrentDisplay()
    private val context = EGL14.eglGetCurrentContext()
    private val surface: android.opengl.EGLSurface
    private var nextFrameNs = 0L

    init {
        val id = IntArray(1)
        check(EGL14.eglQueryContext(display, context, EGL14.EGL_CONFIG_ID, id, 0))
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_CONFIG_ID, id[0], EGL14.EGL_NONE), 0, configs, 0, 1, count, 0) && count[0] > 0)
        surface = EGL14.eglCreateWindowSurface(display, configs[0], encoder.surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(surface != EGL14.EGL_NO_SURFACE) { "Couldn't connect video recording to the renderer" }
    }

    fun draw(nowNs: Long, render: (Int, Int) -> Unit) {
        if (nowNs < nextFrameNs) return
        val interval = 1_000_000_000L / 30
        if (nextFrameNs == 0L) nextFrameNs = nowNs
        do { nextFrameNs += interval } while (nextFrameNs <= nowNs)
        val oldDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        val oldRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
        try {
            check(EGL14.eglMakeCurrent(display, surface, surface, context))
            render(encoder.width, encoder.height)
            check(EGLExt.eglPresentationTimeANDROID(display, surface, (nowNs - encoder.originNs).coerceAtLeast(0)))
            check(EGL14.eglSwapBuffers(display, surface)) { "Video recording lost its rendering surface" }
        } finally {
            check(EGL14.eglMakeCurrent(display, oldDraw, oldRead, context))
        }
    }

    fun close() { EGL14.eglDestroySurface(display, surface) }
    fun fail(error: Exception) { encoder.fail(error.message ?: "Video recording stopped unexpectedly") }
}
