package com.caleb.prism

import android.content.Context
import android.opengl.GLES30.*

/** GL-thread owned targets. Screen and encoder consume the same completed image. */
internal class PostProcessor(context: Context) : AutoCloseable {
    internal class Target : AutoCloseable {
        var width = 0; private set
        var height = 0; private set
        var framebuffer = 0; private set
        var texture = 0; private set
        fun resize(w: Int, h: Int) {
            if (w == width && h == height && framebuffer != 0) return
            close()
            width = w.coerceAtLeast(1); height = h.coerceAtLeast(1)
            val ids = IntArray(1)
            glGenTextures(1, ids, 0); texture = ids[0]
            glBindTexture(GL_TEXTURE_2D, texture)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, null)
            glGenFramebuffers(1, ids, 0); framebuffer = ids[0]
            bind()
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0)
            check(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE) { "Visual effect target unavailable" }
        }
        fun bind() { glBindFramebuffer(GL_FRAMEBUFFER, framebuffer); glViewport(0, 0, width, height) }
        override fun close() {
            if (framebuffer != 0) glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
            if (texture != 0) glDeleteTextures(1, intArrayOf(texture), 0)
            framebuffer = 0; texture = 0; width = 0; height = 0
        }
    }
    private val scene = Target()
    private val outgoing = Target(); private val mixed = Target(); private val frozen = Target()
    private var source = scene
    private val output = Target()
    private val blurA = Target(); private val blurB = Target()
    private val bloomA = Target(); private val bloomB = Target()
    private var final = scene
    private val mixProgram = program(context, "shaders/post_mix.frag")
    private val mixFrom = glGetUniformLocation(mixProgram, "uFrom")
    private val mixTo = glGetUniformLocation(mixProgram, "uTo")
    private val mixAmount = glGetUniformLocation(mixProgram, "uAmount")
    private val blurProgram = program(context, "shaders/post_blur.frag")
    private val compositeProgram = program(context, "shaders/post_composite.frag")
    private val blurImage = glGetUniformLocation(blurProgram, "uImage")
    private val blurStep = glGetUniformLocation(blurProgram, "uStep")
    private val threshold = glGetUniformLocation(blurProgram, "uThreshold")
    private val image = glGetUniformLocation(compositeProgram, "uImage")
    private val blur = glGetUniformLocation(compositeProgram, "uBlur")
    private val bloom = glGetUniformLocation(compositeProgram, "uBloom")
    private val blurAmount = glGetUniformLocation(compositeProgram, "uBlurAmount")
    private val bloomAmount = glGetUniformLocation(compositeProgram, "uBloomAmount")

    fun begin(width: Int, height: Int) { glActiveTexture(GL_TEXTURE0); scene.resize(width, height); scene.bind(); source = scene }

    fun beginOutgoing(width: Int, height: Int) { outgoing.resize(width, height); outgoing.bind() }
    fun freezeScene(): Boolean {
        if (source.width == 0) return false
        frozen.resize(source.width, source.height)
        glBindFramebuffer(GL_READ_FRAMEBUFFER, source.framebuffer)
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, frozen.framebuffer)
        glBlitFramebuffer(0, 0, source.width, source.height, 0, 0, frozen.width, frozen.height, GL_COLOR_BUFFER_BIT, GL_NEAREST)
        return true
    }
    fun mixScenes(amount: Float, useFrozen: Boolean = false) {
        mixed.resize(scene.width, scene.height); mixed.bind()
        glUseProgram(mixProgram)
        glUniform1i(mixFrom, 0); glUniform1i(mixTo, 1); glUniform1f(mixAmount, amount.coerceIn(0f, 1f))
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, if (useFrozen) frozen.texture else outgoing.texture)
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, scene.texture)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4); glActiveTexture(GL_TEXTURE0)
        source = mixed
    }

    fun finish(fx: PostEffects) {
        final = source
        if (!fx.active) return
        val divisor = when (fx.quality) { 0 -> 8; 2 -> 2; else -> 4 }
        val width = (source.width / divisor).coerceAtLeast(1)
        val height = (source.height / divisor).coerceAtLeast(1)
        fun blurred(a: Target, b: Target, radius: Float, cutoff: Float) {
            a.resize(width, height); b.resize(width, height)
            glUseProgram(blurProgram); glUniform1i(blurImage, 0)
            glActiveTexture(GL_TEXTURE0)
            a.bind(); glBindTexture(GL_TEXTURE_2D, source.texture)
            // Radius is defined in image space, so quality presets preserve the look.
            val pixels = 1f + radius.coerceIn(0f, 1f) * 36f
            glUniform2f(blurStep, pixels / source.width, 0f); glUniform1f(threshold, cutoff)
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
            b.bind(); glBindTexture(GL_TEXTURE_2D, a.texture)
            glUniform2f(blurStep, 0f, pixels / source.height); glUniform1f(threshold, -1f)
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        }
        if (fx.blur > 0f) blurred(blurA, blurB, fx.blurRadius, -1f)
        if (fx.bloom > 0f) blurred(bloomA, bloomB, fx.bloomRadius, fx.threshold.coerceIn(0f, 1f))
        output.resize(source.width, source.height); output.bind()
        glUseProgram(compositeProgram)
        glUniform1i(image, 0); glUniform1i(blur, 1); glUniform1i(bloom, 2)
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, source.texture)
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, if (fx.blur > 0f) blurB.texture else source.texture)
        glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D, if (fx.bloom > 0f) bloomB.texture else source.texture)
        glUniform1f(blurAmount, (fx.blur * fx.strength).coerceIn(0f, 1f))
        glUniform1f(bloomAmount, (fx.bloom * fx.strength).coerceIn(0f, 2f))
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        glActiveTexture(GL_TEXTURE0)
        final = output
    }

    fun present(width: Int, height: Int) {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, final.framebuffer)
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)
        glBlitFramebuffer(0, 0, final.width, final.height, 0, 0, width, height, GL_COLOR_BUFFER_BIT, GL_LINEAR)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glViewport(0, 0, width, height)
    }

    override fun close() {
        listOf(scene, outgoing, mixed, frozen, output, blurA, blurB, bloomA, bloomB).forEach { it.close() }
        glDeleteProgram(blurProgram); glDeleteProgram(compositeProgram); glDeleteProgram(mixProgram)
    }

    companion object {
        private fun program(context: Context, fragment: String): Int {
            fun shader(type: Int, asset: String): Int {
                val shader = glCreateShader(type)
                glShaderSource(shader, context.assets.open(asset).bufferedReader().use { it.readText() })
                glCompileShader(shader)
                val ok = IntArray(1); glGetShaderiv(shader, GL_COMPILE_STATUS, ok, 0)
                if (ok[0] == 0) { val error = glGetShaderInfoLog(shader); glDeleteShader(shader); error(error) }
                return shader
            }
            val vertex = shader(GL_VERTEX_SHADER, "shaders/visualizer.vert")
            val pixel = shader(GL_FRAGMENT_SHADER, fragment)
            val program = glCreateProgram()
            glAttachShader(program, vertex); glAttachShader(program, pixel); glLinkProgram(program)
            glDeleteShader(vertex); glDeleteShader(pixel)
            val ok = IntArray(1); glGetProgramiv(program, GL_LINK_STATUS, ok, 0)
            check(ok[0] != 0) { glGetProgramInfoLog(program) }
            return program
        }
    }
}
