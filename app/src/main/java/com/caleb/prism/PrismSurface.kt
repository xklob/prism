package com.caleb.prism

import android.content.Context
import android.opengl.GLES30.*
import android.opengl.GLSurfaceView
import android.os.SystemClock
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
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        preserveEGLContextOnPause = true
        setRenderer(engine)
        renderMode = RENDERMODE_CONTINUOUSLY
    }
    fun update(settings: VisualSettings) {
        engine.settings = settings
        resizeBuffer(width, height, settings.batterySaver)
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
    @Volatile var settings = VisualSettings()
    @Volatile var touchX = 0f
    @Volatile var touchY = 0f
    private var program = 0
    private val uniforms = mutableMapOf<String, Int>()
    private var previousFrame = 0L
    private var time = 0f
    private var rotation = 0f
    private var colorPhase = 0f
    private var morphTime = 0f
    private val smoothedAudio = FloatArray(5)
    private val smoothedBands = FloatArray(32)
    private var frameCounter = 0
    private var statsStart = 0L
    @Volatile var measuredFps = 0f; private set
    private var currentMode = -1
    private var previousMode = 0
    private var transition = 1f
    private var touchSmoothX = 0f
    private var touchSmoothY = 0f
    private val silence = AudioLevels()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
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
            for (name in listOf("uResolution", "uTouch", "uTime", "uRotation", "uColorPhase", "uMorphTime", "uIntensity", "uComplexity", "uSymmetry", "uDistortion", "uZoom", "uLineWidth", "uHue", "uSaturation", "uContrast", "uMode", "uPreviousMode", "uTransition", "uPalette", "uAudio", "uBeat", "uBands[0]")) {
                uniforms[name] = glGetUniformLocation(program, name)
            }
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
        val s = settings
        if (!s.paused) {
            time += dt * s.speed
            rotation += dt * s.rotation * 0.3f
            colorPhase += dt * s.colorSpeed
            morphTime += dt * s.morph
            touchSmoothX += (touchX - touchSmoothX) * 0.07f
            touchSmoothY += (touchY - touchSmoothY) * 0.07f
        }
        if (s.scene.ordinal != currentMode) {
            previousMode = if (currentMode < 0) s.scene.ordinal else currentMode
            currentMode = s.scene.ordinal
            transition = 0f
        }
        if (!s.paused) transition = min(1f, transition + dt * 1.7f)
        if (program != 0) {
            val audio = if (s.scene.reactive) AudioEngine.levels else silence
            if (!s.paused) {
                val blend = 1f - exp(-dt / (0.012f + s.smoothing * 0.35f))
                val target = floatArrayOf(audio.bass * s.bassGain, audio.mid * s.midGain, audio.high * s.trebleGain, audio.energy, audio.beat)
                for (i in smoothedAudio.indices) smoothedAudio[i] += (target[i].coerceIn(0f, 1f) - smoothedAudio[i]) * blend
                for (i in smoothedBands.indices) {
                    val gain = if (i < 10) s.bassGain else if (i < 23) s.midGain else s.trebleGain
                    smoothedBands[i] += ((audio.bands[i] * gain).coerceIn(0f, 1f) - smoothedBands[i]) * blend
                }
            }
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
            glUniform4f(uniforms.getValue("uAudio"), smoothedAudio[0], smoothedAudio[1], smoothedAudio[2], smoothedAudio[3])
            glUniform1f(uniforms.getValue("uBeat"), smoothedAudio[4])
            glUniform1fv(uniforms.getValue("uBands[0]"), 32, smoothedBands, 0)
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        } else { glClearColor(0.03f, 0.03f, 0.08f, 1f); glClear(GL_COLOR_BUFFER_BIT) }
        if (statsStart == 0L) statsStart = started
        frameCounter++
        if (started - statsStart > 2_000_000_000L) {
            measuredFps = frameCounter * 1e9f / (started - statsStart)
            statsStart = started; frameCounter = 0
        }
        val frameMs = if (s.paused) 100L else if (s.batterySaver) 33L else 16L
        val elapsedMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000L
        if (elapsedMs < frameMs) SystemClock.sleep(frameMs - elapsedMs)
    }
}
