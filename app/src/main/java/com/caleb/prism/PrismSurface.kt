package com.caleb.prism

import android.content.Context
import android.opengl.GLES30.*
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.os.Build
import android.os.PowerManager
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
import kotlin.math.exp

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
    fun transitionTo(settings: VisualSettings, seconds: Float) {
        engine.transitionRequest = LookTransition(settings, seconds)
        update(settings)
    }
    private var cancelFrames: (() -> Unit)? = null
    private var resumed = true
    private val power = context.getSystemService(PowerManager::class.java)
    private val thermalListener = PowerManager.OnThermalStatusChangedListener { engine.thermalStatus = it }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        engine.displayHz = display?.refreshRate ?: 60f
        runCatching { engine.thermalStatus = power.currentThermalStatus; power.addThermalStatusListener(context.mainExecutor, thermalListener) }
    }
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
        engine.resumedAtNs = SystemClock.elapsedRealtimeNanos()
        engine.displayHz = display?.refreshRate ?: 60f
        engine.crowd?.let { updateCrowd(it) }
    }
    override fun onDetachedFromWindow() {
        cancelFrames?.invoke(); cancelFrames = null
        runCatching { power.removeThermalStatusListener(thermalListener) }
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
        // The volumetric scene spends more work per pixel. Bound tablet fill cost while
        // retaining the display aspect ratio; recordings keep their own encoder size.
        val edge = if (saver) 1080f else if (engine.settings.scene == Scene.KALEIDOSCOPE) 1440f else Float.POSITIVE_INFINITY
        val scale = min(1f, edge / max(w, h))
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
    private var post: PostProcessor? = null
    private var quality = RenderQuality()
    @Volatile var thermalStatus = 0
    @Volatile var displayHz = 60f
    @Volatile var resumedAtNs = 0L
    @Volatile var qualityReport = RenderQualityReport(); private set
    private val uniforms = mutableMapOf<String, Int>()
    private var previousFrame = 0L
    internal class Layer(var settings: VisualSettings) {
        var time = 0f; var rotation = 0f; var colorPhase = 0f; var morphTime = 0f
        val beatMotion = BeatMotion(); val equalizerMotion = EqualizerMotion()
        val fractalBands = FloatArray(3)
        fun next(settings: VisualSettings) = Layer(settings).also {
            it.time = time; it.rotation = rotation; it.colorPhase = colorPhase; it.morphTime = morphTime
            fractalBands.copyInto(it.fractalBands)
        }
    }
    @Volatile internal var transitionRequest: LookTransition? = null
    private var consumedRequest: LookTransition? = null
    private var layer = Layer(settings)
    private var outgoing: Layer? = null
    private var frozenOutgoing = false
    private var fade = 1f
    private var fadeSeconds = .6f
    private var fadeEffects = PostEffects()
    private var displayedEffects = PostEffects()
    private val silentSpectrum = FloatArray(64)
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
        // A new GL context invalidates all previous object names.
        post = null
        quality = RenderQuality()
        layer = Layer(settings); outgoing = null; frozenOutgoing = false; fade = 1f
        consumedRequest = transitionRequest
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
            for (name in listOf("uSpectrum", "uPeaks", "uEqCount", "uEqSpacing", "uEqMirror", "uEqRadial", "uEqSegments", "uFractalBands", "uFractalShape", "uFractalSpace"))
                uniforms[name] = glGetUniformLocation(program, name)
            val vertices = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer()
                .put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).apply { position(0) }
            val array = IntArray(1); glGenVertexArrays(1, array, 0); glBindVertexArray(array[0])
            val buffer = IntArray(1); glGenBuffers(1, buffer, 0); glBindBuffer(GL_ARRAY_BUFFER, buffer[0])
            glBufferData(GL_ARRAY_BUFFER, 32, vertices, GL_STATIC_DRAW)
            glVertexAttribPointer(0, 2, GL_FLOAT, false, 0, 0); glEnableVertexAttribArray(0)
            glDisable(GL_DEPTH_TEST)
            post = PostProcessor(context)
            previousFrame = 0L
            Log.i("PrismRenderer", "Shaders linked; renderer=${glGetString(GL_RENDERER)}")
        } catch (e: Exception) {
            Log.e("PrismRenderer", "Shader initialization failed", e)
            program = 0
            onError("This device couldn't start the visual renderer. Try reopening prism.")
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
        val frameSeconds = if (previousFrame == 0L || previousFrame < resumedAtNs) 0.0 else (started - previousFrame) / 1e9
        val dt = frameSeconds.toFloat().coerceIn(0f, .25f)
        previousFrame = started
        val following = crowd?.connected == true
        val presentationNs = maxOf(System.nanoTime(), expectedPresentationNs)
        val shared = if (following) crowd?.frame(presentationNs) else null
        val pending = transitionRequest?.takeIf { it !== consumedRequest && !following }
        val s = shared?.cue?.settings ?: pending?.settings ?: settings
        val targetFps = min(if (settings.batterySaver) 30 else settings.targetFps, displayHz.toInt().coerceAtLeast(1))
        val scale = quality.sample(frameSeconds, targetFps, settings.adaptiveQuality, thermalStatus, s.paused)
        val renderWidth = (viewportWidth * scale).toInt().coerceAtLeast(1)
        val renderHeight = (viewportHeight * scale).toInt().coerceAtLeast(1)
        qualityReport = RenderQualityReport(scale, renderWidth, renderHeight, thermalStatus, targetFps)
        val request = transitionRequest
        val requested = !following && request != null && request !== consumedRequest
        val changePattern = !following && s.scene != layer.settings.scene
        if (requested || changePattern) {
            val duration = if (requested) request!!.seconds else .6f
            frozenOutgoing = (outgoing != null || frozenOutgoing) && post?.freezeScene() == true
            outgoing = if (frozenOutgoing) null else layer
            fadeEffects = displayedEffects
            layer = layer.next(s)
            fadeSeconds = duration.coerceIn(0f, 8f)
            fade = if (fadeSeconds <= 0f) 1f else 0f
            consumedRequest = request
        }
        if (following) { outgoing = null; frozenOutgoing = false; fade = 1f; consumedRequest = request }
        layer.settings = s
        post?.begin(renderWidth, renderHeight)
        if (following) {
            touchSmoothX = 0f; touchSmoothY = 0f
            if (shared != null) {
                currentMode = s.scene.id; previousMode = shared.cue.previousScene
                transition = ((shared.time - shared.cue.transitionAt) / 600).toFloat().coerceIn(0f, 1f)
            }
            EGLExt.eglPresentationTimeANDROID(EGL14.eglGetCurrentDisplay(), EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW), presentationNs)
        } else if (!s.paused) {
            val response = 1f - exp(-dt / .2f)
            touchSmoothX += (touchX - touchSmoothX) * response
            touchSmoothY += (touchY - touchSmoothY) * response
        }
        if (following && (shared == null || shared.dark || shared.cue.diagnostic)) {
            val white = if (shared != null && !shared.dark) shared.motion.flash else 0f
            glClearColor(white, white, white, 1f); glClear(GL_COLOR_BUFFER_BIT)
        } else drawLayer(layer, s, dt, following, shared, renderWidth, renderHeight)
        if (!following && fade < 1f) {
            outgoing?.let { old ->
                post?.beginOutgoing(renderWidth, renderHeight)
                drawLayer(old, old.settings.keepingSession(s), dt, false, null, renderWidth, renderHeight)
            }
            val amount = fade * fade * (3f - 2f * fade)
            post?.mixScenes(amount, frozenOutgoing)
            displayedEffects = blendEffects(fadeEffects, s.effects, amount)
            fade = (fade + dt / fadeSeconds).coerceAtMost(1f)
        } else {
            outgoing = null; frozenOutgoing = false
            displayedEffects = if (following) PostEffects() else s.effects
        }
        post?.finish(displayedEffects)
        post?.present(viewportWidth, viewportHeight)
        recording?.let { target ->
            try {
                target.draw(System.nanoTime()) { width, height ->
                    post?.present(width, height)
                }
            } catch (error: Exception) {
                target.fail(error)
                target.close()
                recording = null
            } finally {
                glViewport(0, 0, viewportWidth, viewportHeight)
            }
        }
        if (statsStart == 0L) statsStart = started
        frameCounter++
        if (started - statsStart > 2_000_000_000L) {
            measuredFps = frameCounter * 1e9f / (started - statsStart)
            crowd?.measuredFps = measuredFps
            statsStart = started; frameCounter = 0
        }
        val frameMs = if (s.paused && recording == null) 100L else (1000L / targetFps)
        val elapsedMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000L
        if (!following && elapsedMs < frameMs) SystemClock.sleep(frameMs - elapsedMs)
    }
    private fun drawLayer(layer: Layer, s: VisualSettings, dt: Float, following: Boolean,
                          shared: CrowdFrame?, renderWidth: Int, renderHeight: Int) {
        if (following && shared != null) {
            val seconds = (shared.time / 1000).toFloat()
            layer.time = seconds * s.speed; layer.rotation = seconds * s.rotation * .3f
            layer.colorPhase = seconds * s.colorSpeed; layer.morphTime = seconds * s.morph
        } else if (!following && !s.paused) {
            layer.time += dt * s.speed; layer.rotation += dt * s.rotation * .3f
            layer.colorPhase += dt * s.colorSpeed; layer.morphTime += dt * s.morph
        }
        if (program == 0) { glClearColor(.03f, .03f, .08f, 1f); glClear(GL_COLOR_BUFFER_BIT); return }
            val motion = shared?.motion ?: layer.beatMotion.update(AudioEngine.rhythm, AudioEngine.levels, System.nanoTime()/1e9, s)
            glUseProgram(program)
            glUniform2f(uniforms.getValue("uResolution"), renderWidth.toFloat(), renderHeight.toFloat())
            glUniform1f(uniforms.getValue("uTime"), layer.time)
            glUniform1f(uniforms.getValue("uRotation"), layer.rotation)
            glUniform1f(uniforms.getValue("uColorPhase"), layer.colorPhase)
            glUniform1f(uniforms.getValue("uMorphTime"), layer.morphTime)
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
            glUniform1i(uniforms.getValue("uMode"), s.scene.id)
            glUniform1i(uniforms.getValue("uPreviousMode"), if (following) previousMode else s.scene.id)
            glUniform1f(uniforms.getValue("uTransition"), if (following) transition else 1f)
            glUniform1i(uniforms.getValue("uPalette"), s.palette.ordinal)
            glUniform4f(uniforms.getValue("uRhythm"), motion.beat, motion.bar, motion.flow, motion.texture)
            glUniform2f(uniforms.getValue("uDownbeat"), motion.flash, motion.inversion)
            val liveFractal = !following && AudioEngine.status.value.running && s.audioEnabled
            val levels = AudioEngine.levels
            for (i in 0..2) {
                val target = if (liveFractal) (when (i) { 0 -> levels.bass; 1 -> levels.mid; else -> levels.high } * s.audioAmount).coerceIn(0f, 2f) else 0f
                if (!liveFractal) layer.fractalBands[i] = 0f
                else if (!s.paused) {
                    val response = if (target > layer.fractalBands[i]) .045f else .22f
                    layer.fractalBands[i] += (target - layer.fractalBands[i]) * (1f - exp(-dt / response))
                }
            }
            glUniform3f(uniforms.getValue("uFractalBands"), layer.fractalBands[0], layer.fractalBands[1], layer.fractalBands[2])
            glUniform1i(uniforms.getValue("uFractalShape"), s.kaleidoscopeShape)
            glUniform1f(uniforms.getValue("uFractalSpace"), s.kaleidoscopeSpace)
            if (!s.paused) layer.equalizerMotion.update(
                if (!following && AudioEngine.status.value.running && s.audioEnabled) AudioEngine.levels.spectrum else silentSpectrum,
                s.eqBuckets, dt, s.eqGain*s.audioAmount, s.eqFalloff, s.eqPeakHold)
            glUniform1fv(uniforms.getValue("uSpectrum"), 64, layer.equalizerMotion.bars, 0)
            glUniform1fv(uniforms.getValue("uPeaks"), 64, layer.equalizerMotion.peaks, 0)
            glUniform1i(uniforms.getValue("uEqCount"), s.eqBuckets)
            glUniform1f(uniforms.getValue("uEqSpacing"), s.eqSpacing)
            glUniform1i(uniforms.getValue("uEqMirror"), if (s.eqMirror) 1 else 0)
            glUniform1i(uniforms.getValue("uEqRadial"), if (s.eqRadial) 1 else 0)
            glUniform1i(uniforms.getValue("uEqSegments"), if (s.eqSegments) 1 else 0)
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
    }
}
