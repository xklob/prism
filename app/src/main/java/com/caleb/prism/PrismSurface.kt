package com.caleb.prism

import android.content.Context
import android.opengl.GLES30.*
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.os.Build
import android.view.Choreographer
import android.opengl.EGL14
import android.opengl.EGLExt
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import kotlin.math.min

class PrismSurface @JvmOverloads constructor(context: Context, onError: (String) -> Unit = {}) : GLSurfaceView(context) {
    val engine = PrismRenderer(context, onError)
    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser { egl, display ->
            val attributes = intArrayOf(0x3024, 8, 0x3023, 8, 0x3022, 8, 0x3021, 8,
                0x3040, 0x40, 0x3033, 4, 0x3142, 1, 0x3038) // GLES3, window, EGL_RECORDABLE_ANDROID
            val count = IntArray(1)
            check(egl.eglChooseConfig(display, attributes, null, 0, count) && count[0] > 0) { "No recordable GLES configuration" }
            val configs = arrayOfNulls<EGLConfig>(count[0])
            check(egl.eglChooseConfig(display, attributes, configs, configs.size, count))
            configs[0]
        }
        preserveEGLContextOnPause = true
        setRenderer(engine)
        renderMode = RENDERMODE_CONTINUOUSLY
    }
    fun update(settings: VisualSettings) {
        engine.settings = settings
        resizeBuffer(width, height, settings.batterySaver)
    }
    private var cancelFrames: (() -> Unit)? = null
    private var resumed = true
    fun updateCrowd(client: CrowdClient) {
        engine.crowd = client
        val following = client.connected
        renderMode = if (following) RENDERMODE_WHEN_DIRTY else RENDERMODE_CONTINUOUSLY
        if (following && resumed && cancelFrames == null) startFrames()
        if (!following) { cancelFrames?.invoke(); cancelFrames = null }
    }
    private fun startFrames() {
        val choreographer = Choreographer.getInstance()
        if (Build.VERSION.SDK_INT >= 33) {
            val callback = object : Choreographer.VsyncCallback {
                override fun onVsync(data: Choreographer.FrameData) {
                    if (cancelFrames == null) return
                    engine.expectedPresentationNs = data.preferredFrameTimeline.expectedPresentationTimeNanos
                    requestRender()
                    choreographer.postVsyncCallback(this)
                }
            }
            cancelFrames = { choreographer.removeVsyncCallback(callback) }
            choreographer.postVsyncCallback(callback)
        } else {
            val callback = object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    if (cancelFrames == null) return
                    val hz = display?.refreshRate?.takeIf { it > 0 } ?: 60f
                    engine.expectedPresentationNs = frameTimeNanos + (1e9 / hz).toLong()
                    requestRender()
                    choreographer.postFrameCallback(this)
                }
            }
            cancelFrames = { choreographer.removeFrameCallback(callback) }
            choreographer.postFrameCallback(callback)
        }
    }
    override fun onPause() {
        resumed = false; cancelFrames?.invoke(); cancelFrames = null
        super.onPause()
    }
    override fun onResume() {
        super.onResume()
        resumed = true
        engine.crowd?.let { updateCrowd(it) }
    }
    override fun onDetachedFromWindow() {
        cancelFrames?.invoke(); cancelFrames = null
        super.onDetachedFromWindow()
    }
    internal fun beginRecording(encoder: SessionEncoder, started: () -> Unit) = queueEvent {
        try {
            engine.recording = RecordingGlTarget(encoder)
            started()
        } catch (error: Exception) { encoder.fail(error.message ?: "Couldn't start video recording") }
    }
    internal fun endRecording(done: () -> Unit) = queueEvent {
        try { engine.recording?.close() } finally { engine.recording = null; done() }
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resizeBuffer(w, h, engine.settings.batterySaver)
    }
    private var bufferWidth = 0
    private var bufferHeight = 0
    private fun resizeBuffer(w: Int, h: Int, saver: Boolean) {
        if (w == 0 || h == 0) return
        val scale = if (saver) min(1f, 1080f / max(w, h)) else 1f
        val bw = (w * scale).toInt().coerceAtLeast(1)
        val bh = (h * scale).toInt().coerceAtLeast(1)
        if (bw != bufferWidth || bh != bufferHeight) {
            bufferWidth = bw; bufferHeight = bh
            holder.setFixedSize(bw, bh)
        }
    }
}

class PrismRenderer(private val context: Context, private val onError: (String) -> Unit) : GLSurfaceView.Renderer {
    internal var recording: RecordingGlTarget? = null
    private var viewportWidth = 1
    private var viewportHeight = 1
    @Volatile var settings = VisualSettings()
    @Volatile var crowd: CrowdClient? = null
    @Volatile var expectedPresentationNs = 0L
    @Volatile var touchX = 0f
    @Volatile var touchY = 0f
    private var program = 0
    private val uniforms = mutableMapOf<String, Int>()
    private var previousFrame = 0L
    private var time = 0f
    private var rotation = 0f
    private var colorPhase = 0f
    private var morphTime = 0f
    private val beatMotion = BeatMotion()
    private var frameCounter = 0
    private var statsStart = 0L
    @Volatile var measuredFps = 0f; private set
    private var currentMode = -1
    private var previousMode = 0
    private var transition = 1f
    private var touchSmoothX = 0f
    private var touchSmoothY = 0f

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        recording?.let {
            it.fail(IllegalStateException("Rendering restarted. The recording was stopped."))
            it.close()
        }
        recording = null
        try {
            fun compile(type: Int, asset: String): Int {
                val shader = glCreateShader(type)
                glShaderSource(shader, context.assets.open(asset).bufferedReader().use { it.readText() })
                glCompileShader(shader)
                val compiled = IntArray(1)
                glGetShaderiv(shader, GL_COMPILE_STATUS, compiled, 0)
                check(compiled[0] != 0) { glGetShaderInfoLog(shader) }
                return shader
            }
            val vertex = compile(GL_VERTEX_SHADER, "shaders/visualizer.vert")
            val fragment = compile(GL_FRAGMENT_SHADER, "shaders/visualizer.frag")
            program = glCreateProgram()
            glAttachShader(program, vertex); glAttachShader(program, fragment); glLinkProgram(program)
            glDeleteShader(vertex); glDeleteShader(fragment)
            val linked = IntArray(1)
            glGetProgramiv(program, GL_LINK_STATUS, linked, 0)
            check(linked[0] != 0) { glGetProgramInfoLog(program) }
            uniforms.clear()
            for (name in listOf("uResolution", "uTouch", "uTime", "uRotation", "uColorPhase", "uMorphTime", "uIntensity", "uComplexity", "uSymmetry", "uDistortion", "uZoom", "uLineWidth", "uHue", "uSaturation", "uContrast", "uMode", "uPreviousMode", "uTransition", "uPalette", "uRhythm")) {
                uniforms[name] = glGetUniformLocation(program, name)
            }
            uniforms["uDownbeat"]=glGetUniformLocation(program,"uDownbeat")
            val vertices = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer()
                .put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).apply { position(0) }
            val array = IntArray(1); glGenVertexArrays(1, array, 0); glBindVertexArray(array[0])
            val buffer = IntArray(1); glGenBuffers(1, buffer, 0); glBindBuffer(GL_ARRAY_BUFFER, buffer[0])
            glBufferData(GL_ARRAY_BUFFER, 32, vertices, GL_STATIC_DRAW)
            glVertexAttribPointer(0, 2, GL_FLOAT, false, 0, 0); glEnableVertexAttribArray(0)
            glDisable(GL_DEPTH_TEST)
            previousFrame = 0L
            Log.i("PrismRenderer", "Shaders linked; renderer=${glGetString(GL_RENDERER)}")
        } catch (e: Exception) {
            Log.e("PrismRenderer", "Shader initialization failed", e)
            program = 0
            onError("This device couldn't start the visual renderer. Try reopening Prism.")
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width; viewportHeight = height
        glViewport(0, 0, width, height)
        if (program != 0) {
            glUseProgram(program)
            glUniform2f(uniforms.getValue("uResolution"), width.toFloat(), height.toFloat())
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        val started = SystemClock.elapsedRealtimeNanos()
        val dt = if (previousFrame == 0L) 0f else ((started - previousFrame) / 1e9f).coerceIn(0f, 0.05f)
        previousFrame = started
        val following = crowd?.connected == true
        val presentationNs = maxOf(System.nanoTime(), expectedPresentationNs)
        val shared = if (following) crowd?.frame(presentationNs) else null
        val s = shared?.cue?.settings ?: settings
        if (following && shared != null) {
            val seconds = (shared.time / 1000).toFloat()
            time = seconds * s.speed
            rotation = seconds * s.rotation * .3f
            colorPhase = seconds * s.colorSpeed
            morphTime = seconds * s.morph
            touchSmoothX = 0f; touchSmoothY = 0f
        } else if (!following && !s.paused) {
            time += dt * s.speed
            rotation += dt * s.rotation * 0.3f
            colorPhase += dt * s.colorSpeed
            morphTime += dt * s.morph
            touchSmoothX += (touchX - touchSmoothX) * 0.07f
            touchSmoothY += (touchY - touchSmoothY) * 0.07f
        }
        if (following && shared != null) {
            currentMode = s.scene.id; previousMode = shared.cue.previousScene
            transition = ((shared.time - shared.cue.transitionAt) / 600).toFloat().coerceIn(0f, 1f)
        } else if (s.scene.id != currentMode) {
            previousMode = if (currentMode < 0) s.scene.id else currentMode
            currentMode = s.scene.id
            transition = 0f
        }
        if (!following) transition = min(1f, transition + dt * 1.7f)
        if (following) {
            EGLExt.eglPresentationTimeANDROID(EGL14.eglGetCurrentDisplay(), EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW), presentationNs)
        }
        if (following && (shared == null || shared.dark || shared.cue.diagnostic)) {
            val white = if (shared != null && !shared.dark) shared.motion.flash else 0f
            glClearColor(white, white, white, 1f); glClear(GL_COLOR_BUFFER_BIT)
        } else if (program != 0) {
            val motion = shared?.motion ?: beatMotion.update(AudioEngine.rhythm, AudioEngine.levels, System.nanoTime()/1e9, s)
            glUseProgram(program)
            glUniform1f(uniforms.getValue("uTime"), time)
            glUniform1f(uniforms.getValue("uRotation"), rotation)
            glUniform1f(uniforms.getValue("uColorPhase"), colorPhase)
            glUniform1f(uniforms.getValue("uMorphTime"), morphTime)
            glUniform1f(uniforms.getValue("uIntensity"), s.intensity)
            glUniform1f(uniforms.getValue("uComplexity"), s.complexity)
            glUniform1f(uniforms.getValue("uSymmetry"), s.symmetry.toFloat())
            glUniform1f(uniforms.getValue("uDistortion"), s.distortion)
            glUniform1f(uniforms.getValue("uZoom"), s.zoom)
            glUniform1f(uniforms.getValue("uLineWidth"), s.lineWidth)
            glUniform1f(uniforms.getValue("uHue"), s.hue)
            glUniform1f(uniforms.getValue("uSaturation"), s.saturation)
            glUniform1f(uniforms.getValue("uContrast"), s.contrast)
            glUniform2f(uniforms.getValue("uTouch"), touchSmoothX, touchSmoothY)
            glUniform1i(uniforms.getValue("uMode"), currentMode)
            glUniform1i(uniforms.getValue("uPreviousMode"), previousMode)
            glUniform1f(uniforms.getValue("uTransition"), transition)
            glUniform1i(uniforms.getValue("uPalette"), s.palette.ordinal)
            glUniform4f(uniforms.getValue("uRhythm"), motion.beat, motion.bar, motion.flow, motion.texture)
            glUniform2f(uniforms.getValue("uDownbeat"), motion.flash, motion.inversion)
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        } else { glClearColor(0.03f, 0.03f, 0.08f, 1f); glClear(GL_COLOR_BUFFER_BIT) }
        recording?.let { target ->
            try {
                target.draw(System.nanoTime()) { width, height ->
                    glViewport(0, 0, width, height)
                    glUniform2f(uniforms.getValue("uResolution"), width.toFloat(), height.toFloat())
                    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
                }
            } catch (error: Exception) {
                target.fail(error)
                target.close()
                recording = null
            } finally {
                glViewport(0, 0, viewportWidth, viewportHeight)
                if (program != 0) glUniform2f(uniforms.getValue("uResolution"), viewportWidth.toFloat(), viewportHeight.toFloat())
            }
        }
        if (statsStart == 0L) statsStart = started
        frameCounter++
        if (started - statsStart > 2_000_000_000L) {
            measuredFps = frameCounter * 1e9f / (started - statsStart)
            crowd?.measuredFps = measuredFps
            statsStart = started; frameCounter = 0
        }
        val frameMs = if (s.paused && recording == null) 100L else if (s.batterySaver) 33L else 16L
        val elapsedMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000L
        if (!following && elapsedMs < frameMs) SystemClock.sleep(frameMs - elapsedMs)
    }
}
