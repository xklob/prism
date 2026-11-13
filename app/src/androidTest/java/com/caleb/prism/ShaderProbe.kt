package com.caleb.prism

import android.content.Context
import android.graphics.Bitmap
import android.opengl.EGL14.*
import android.opengl.EGLConfig
import android.opengl.GLES30.*
import org.junit.Assert.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** Render the shipped shader at fixed animation phases, so only audio can change the pixels. */
class ShaderProbe(context: Context, private val evidence: File, private val width: Int = 256, private val height: Int = 384) : AutoCloseable {
    private val display = eglGetDisplay(EGL_DEFAULT_DISPLAY)
    private val eglContext: android.opengl.EGLContext
    private val surface: android.opengl.EGLSurface
    private val program: Int

    init {
        assertTrue(eglInitialize(display, IntArray(2), 0, IntArray(2), 1))
        val configs = arrayOfNulls<EGLConfig>(1)
        assertTrue(eglChooseConfig(display, intArrayOf(EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
            EGL_RENDERABLE_TYPE, 0x40, EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8,
            EGL_ALPHA_SIZE, 8, EGL_NONE), 0, configs, 0, 1, IntArray(1), 0))
        val config = requireNotNull(configs[0])
        eglContext = eglCreateContext(display, config, EGL_NO_CONTEXT, intArrayOf(EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE), 0)
        surface = eglCreatePbufferSurface(display, config, intArrayOf(EGL_WIDTH, width, EGL_HEIGHT, height, EGL_NONE), 0)
        assertTrue(eglMakeCurrent(display, surface, surface, eglContext))
        fun compile(type: Int, asset: String): Int {
            val shader = glCreateShader(type)
            glShaderSource(shader, context.assets.open(asset).bufferedReader().use { it.readText() })
            glCompileShader(shader)
            val result = IntArray(1)
            glGetShaderiv(shader, GL_COMPILE_STATUS, result, 0)
            assertEquals(glGetShaderInfoLog(shader), 1, result[0])
            return shader
        }
        val vertex = compile(GL_VERTEX_SHADER, "shaders/visualizer.vert")
        val fragment = compile(GL_FRAGMENT_SHADER, "shaders/visualizer.frag")
        program = glCreateProgram()
        glAttachShader(program, vertex); glAttachShader(program, fragment); glLinkProgram(program)
        val linked = IntArray(1)
        glGetProgramiv(program, GL_LINK_STATUS, linked, 0)
        assertEquals(glGetProgramInfoLog(program), 1, linked[0])
        glDeleteShader(vertex); glDeleteShader(fragment)
        val vertices = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).apply { position(0) }
        val array = IntArray(1); glGenVertexArrays(1, array, 0); glBindVertexArray(array[0])
        val buffer = IntArray(1); glGenBuffers(1, buffer, 0); glBindBuffer(GL_ARRAY_BUFFER, buffer[0])
        glBufferData(GL_ARRAY_BUFFER, 32, vertices, GL_STATIC_DRAW)
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 0, 0); glEnableVertexAttribArray(0)
        glViewport(0, 0, width, height)
    }

    private fun render(scene: Scene, audio: MusicalMotion, time: Float = 4f,
                       spectrum: FloatArray = FloatArray(64), s: VisualSettings = VisualSettings.defaults(scene),
                       fractalBands: FloatArray = FloatArray(3)): ByteArray {
        glUseProgram(program)
        fun f(name: String, value: Float) = glUniform1f(glGetUniformLocation(program, name), value)
        fun i(name: String, value: Int) = glUniform1i(glGetUniformLocation(program, name), value)
        glUniform2f(glGetUniformLocation(program, "uResolution"), width.toFloat(), height.toFloat())
        glUniform2f(glGetUniformLocation(program, "uTouch"), 0f, 0f)
        f("uTime", time); f("uRotation", 0.15f); f("uMorphTime", 2f); f("uColorPhase", 0.1f)
        f("uIntensity", s.intensity); f("uComplexity", s.complexity); f("uSymmetry", s.symmetry.toFloat())
        f("uDistortion", s.distortion); f("uZoom", s.zoom); f("uLineWidth", s.lineWidth)
        f("uHue", s.hue); f("uSaturation", s.saturation); f("uContrast", s.contrast)
        i("uMode", scene.id); i("uPreviousMode", scene.id); f("uTransition", 1f); i("uPalette", s.palette.ordinal)
        glUniform4f(glGetUniformLocation(program, "uRhythm"), audio.beat, audio.bar, audio.flow, audio.texture)
        glUniform2f(glGetUniformLocation(program, "uDownbeat"), audio.flash, audio.inversion)
        glUniform1fv(glGetUniformLocation(program,"uSpectrum"),64,spectrum,0)
        glUniform1fv(glGetUniformLocation(program,"uPeaks"),64,FloatArray(64),0)
        i("uEqCount",s.eqBuckets); f("uEqSpacing",s.eqSpacing)
        i("uEqMirror",if (s.eqMirror) 1 else 0); i("uEqRadial",if (s.eqRadial) 1 else 0)
        i("uEqSegments",if (s.eqSegments) 1 else 0)
        i("uFractalShape", s.kaleidoscopeShape); f("uFractalSpace", s.kaleidoscopeSpace)
        glUniform3f(glGetUniformLocation(program,"uFractalBands"),fractalBands[0],fractalBands[1],fractalBands[2])
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        val buffer = ByteBuffer.allocateDirect(width * height * 4)
        glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, buffer)
        assertEquals("Shader render must not generate GL errors", GL_NO_ERROR, glGetError())
        return ByteArray(buffer.capacity()).also { buffer.get(it) }
    }

    fun verifyNewPatterns() {
        for (scene in Scene.entries.filter { it != Scene.EQUALIZER })
            save(render(scene,MusicalMotion(),15f),"polished-${scene.name}.png")
        val wide=render(Scene.MANDELBROT,MusicalMotion(),0f)
        val deep=render(Scene.MANDELBROT,MusicalMotion(),24f)
        assertFalse("The Mandelbrot camera must actually zoom",wide.contentEquals(deep))
        save(wide,"mandelbrot-wide.png"); save(deep,"mandelbrot-deep.png")
        val scene=Scene.EQUALIZER
        val silence=render(scene,MusicalMotion())
        val spectrum=FloatArray(64) { if (it in 2..6) .8f else .1f }
        val bars=render(scene,MusicalMotion(),spectrum=spectrum)
        assertFalse("Measured frequencies must drive the equalizer",silence.contentEquals(bars))
        save(bars,"equalizer-bars.png")
        for (count in listOf(8,16,32,64)) for (radial in listOf(false,true)) {
            val settings=VisualSettings.defaults(scene).copy(eqBuckets=count,eqMirror=true,eqRadial=radial)
            val result=render(scene,MusicalMotion(),spectrum=spectrum,s=settings)
            assertFalse("Bucket count and layout must reach the renderer",bars.contentEquals(result))
            save(result,"equalizer-$count-${if (radial) "radial" else "mirror"}.png")
        }
        assertArrayEquals("No signal restores the same empty spectrum",silence,render(scene,MusicalMotion()))
    }

    fun verifyKaleidoscope() {
        val scene = Scene.KALEIDOSCOPE
        val defaults = VisualSettings.defaults(scene)
        val ambient = render(scene, MusicalMotion())
        save(ambient, "kaleidoscope-chamber.png")
        val lit = ambient.indices.count { it % 4 != 3 && (ambient[it].toInt() and 255) > 35 }
        assertTrue("Fractal filaments must be visible", lit > width * height / 3)
        assertTrue("Fractal must retain dark space", lit < width * height * 2.7)
        for (band in 0..2) {
            val bands = FloatArray(3).also { it[band] = .8f }
            val active = render(scene, MusicalMotion(), fractalBands = bands)
            assertFalse("Each frequency range must reshape the fractal", ambient.contentEquals(active))
            save(active, "kaleidoscope-band-$band.png")
        }
        for (shape in 1..2) {
            val result = render(scene, MusicalMotion(), s = defaults.copy(kaleidoscopeShape = shape))
            assertFalse("Space presets must change the chamber", ambient.contentEquals(result))
            save(result, "kaleidoscope-space-$shape.png")
        }
        for (s in listOf(defaults.copy(kaleidoscopeSpace = 1.8f), defaults.copy(lineWidth = 2f),
            defaults.copy(complexity = .1f), defaults.copy(distortion = 1.4f))) {
            assertFalse("Fractal controls must affect the render", ambient.contentEquals(render(scene, MusicalMotion(), s = s)))
        }
        assertFalse("The camera must travel", ambient.contentEquals(render(scene, MusicalMotion(), 18f)))
        assertArrayEquals("Turning audio off restores the ambient scene", ambient, render(scene, MusicalMotion()))
    }

    fun verifyResponse(scene: Scene, input: MusicalMotion, label: String) {
        val ambient = render(scene, MusicalMotion())
        val active = render(scene, input)
        var difference = 0.0
        var changed = 0
        for (pixel in 0 until width * height) {
            var local = 0
            for (channel in 0..2) local += abs((ambient[pixel * 4 + channel].toInt() and 255) - (active[pixel * 4 + channel].toInt() and 255))
            difference += local
            if (local > 30) changed++
        }
        difference /= width * height * 3 * 255.0
        val fraction = changed.toDouble() / (width * height)
        File(evidence, "shader-response.txt").appendText("${scene.name} $label rgb_difference=$difference changed_fraction=$fraction beat=${input.beat} bar=${input.bar} flow=${input.flow}\n")
        assertTrue("${scene.title} / $label must change visibly: $difference", difference > 0.025)
        assertTrue("${scene.title} / $label must affect substantial geometry: $fraction", fraction > 0.15)
        val off = BeatMotion().update(RhythmState(),AudioLevels(),10.0,VisualSettings(audioEnabled=false))
        assertArrayEquals("Audio off must exactly restore the ambient render", ambient, render(scene, off))
        if (label == "captured") { save(ambient, "${scene.name}-ambient.png"); save(active, "${scene.name}-audio.png") }
    }

    fun verifyDownbeatColors(scene: Scene) {
        val ambient=render(scene,MusicalMotion())
        val inverted=render(scene,MusicalMotion(inversion=1f))
        val fading=render(scene,MusicalMotion(inversion=.5f))
        val flash=render(scene,MusicalMotion(inversion=1f,flash=1f))
        for (pixel in 0 until width*height) for (channel in 0..2) {
            val n=pixel*4+channel
            assertEquals("Every color channel must invert",(255-(ambient[n].toInt() and 255)).toDouble(),(inverted[n].toInt() and 255).toDouble(),1.0)
            assertEquals("Halfway fade must blend original and inverted colors",127.5,(fading[n].toInt() and 255).toDouble(),1.0)
            assertEquals("Strobe covers the entire visual",255,flash[n].toInt() and 255)
        }
        assertArrayEquals("Finished fade restores original colors exactly",ambient,render(scene,MusicalMotion()))
        save(inverted,"${scene.name}-inverted.png")
        save(flash,"${scene.name}-flash.png")
        File(evidence,"shader-response.txt").appendText("${scene.name} flash=white inversion=exact fade=verified\n")
    }

    private fun save(bytes: ByteArray, name: String) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) for (x in 0 until width) {
            val n = ((height - 1 - y) * width + x) * 4
            bitmap.setPixel(x, y, android.graphics.Color.rgb(bytes[n].toInt() and 255, bytes[n+1].toInt() and 255, bytes[n+2].toInt() and 255))
        }
        File(evidence, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    override fun close() {
        glDeleteProgram(program)
        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT)
        eglDestroySurface(display, surface)
        eglDestroyContext(display, eglContext)
        // Do not eglTerminate: the activity's GLSurfaceView uses the same display.
    }
}
