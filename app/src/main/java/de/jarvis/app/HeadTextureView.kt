package de.jarvis.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.SystemClock
import android.view.MotionEvent
import android.view.TextureView
import kotlin.math.abs
import kotlin.math.sin

/**
 * Der echte 3D-Kopf als normale View (TextureView) – passt in scrollende Seiten wie die Startseite.
 * Eigener kleiner OpenGL-Thread, läuft nur, solange die View sichtbar ist.
 */
@SuppressLint("ViewConstructor")
class HeadTextureView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    @Volatile var mode = 0
    @Volatile var levelSource: (() -> Float)? = null
    var onTap: (() -> Unit)? = null
    @Volatile private var dragYaw = 0f
    @Volatile private var visible = true
    private var thread: RenderThread? = null

    init { isOpaque = false; surfaceTextureListener = this }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        thread = RenderThread(st, w, h).also { it.start() }
    }
    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { thread?.w = w; thread?.h = h }
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { thread?.quit(); thread = null; return true }
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); visible = isVisible }

    private var downX = 0f; private var lastX = 0f; private var moved = false
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; lastX = e.x; moved = false; parent?.requestDisallowInterceptTouchEvent(false) }
            MotionEvent.ACTION_MOVE -> {
                if (abs(e.x - downX) > 14) { moved = true; parent?.requestDisallowInterceptTouchEvent(true) }
                if (moved) dragYaw += (e.x - lastX) / width.coerceAtLeast(1) * 4f
                lastX = e.x
            }
            MotionEvent.ACTION_UP -> if (!moved) onTap?.invoke()
        }
        return true
    }

    private inner class RenderThread(private val st: SurfaceTexture, @Volatile var w: Int, @Volatile var h: Int) : Thread("JarvisHead") {
        @Volatile private var running = true
        fun quit() { running = false; try { join(500) } catch (_: InterruptedException) {} }

        override fun run() {
            val dpy: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val ver = IntArray(2); if (!EGL14.eglInitialize(dpy, ver, 0, ver, 1)) return
            val attrs = intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 16, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE)
            val cfgs = arrayOfNulls<EGLConfig>(1); val num = IntArray(1)
            if (!EGL14.eglChooseConfig(dpy, attrs, 0, cfgs, 0, 1, num, 0) || num[0] == 0) return
            val ctx: EGLContext = EGL14.eglCreateContext(dpy, cfgs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(0x3098, 2, EGL14.EGL_NONE), 0)
            val surf: EGLSurface = EGL14.eglCreateWindowSurface(dpy, cfgs[0], st, intArrayOf(EGL14.EGL_NONE), 0)
            EGL14.eglMakeCurrent(dpy, surf, surf, ctx)
            val mesh = try { HeadMesh(context).also { it.create() } } catch (_: Throwable) { null }
            val start = SystemClock.elapsedRealtime()
            var fade = 0f
            try {
                while (running && mesh != null) {
                    if (!visible) { sleep(120); continue }
                    val t0 = SystemClock.elapsedRealtime()
                    val t = (t0 - start) / 1000f
                    GLES20.glViewport(0, 0, w, h)
                    GLES20.glClearColor(0f, 0f, 0f, 0f)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
                    fade = (fade + 0.05f).coerceAtMost(1f)
                    val lv = if (mode == 3) 0.45f + 0.4f * abs(sin(t * 7.5f)) * (0.6f + 0.4f * sin(t * 1.9f)) else (levelSource?.invoke() ?: 0f)
                    mesh.draw(w, h, -0.45f + sin(t * 0.35f) * 0.28f + dragYaw, t, lv.coerceIn(0f, 1f), mode, fade)
                    EGL14.eglSwapBuffers(dpy, surf)
                    val wait = 40 - (SystemClock.elapsedRealtime() - t0)   // ~25 Bilder/s reichen und sparen Akku
                    if (wait > 0) sleep(wait)
                }
            } catch (_: InterruptedException) {
            } finally {
                EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(dpy, surf); EGL14.eglDestroyContext(dpy, ctx)
            }
        }
    }
}
