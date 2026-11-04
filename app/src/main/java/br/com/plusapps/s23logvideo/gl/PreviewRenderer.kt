package br.com.plusapps.s23logvideo.gl

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import br.com.plusapps.s23logvideo.color.LogCurve
import kotlinx.coroutines.CompletableDeferred
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Opções de monitoramento (Fase 4). Tudo só na tela: o arquivo gravado nunca passa por aqui. */
data class MonitorOptions(
    /** Aplica a LUT carregada em [PreviewRenderer.setLut] (Log → Rec.709 ou um look). */
    val lut: Boolean = false,
    val falseColor: Boolean = false,
    val zebra: Boolean = false,
    /** Limite da zebra, em fração do sinal gravado (0..1). */
    val zebraLevel: Float = 0.90f,
    val peaking: Boolean = false,
    /** Sensibilidade do peaking: limite do gradiente (menor = mais bordas). */
    val peakingThreshold: Float = 0.12f,
    val peakingColor: Int = 0xFFFF1744.toInt(),
    val scopes: Boolean = false,
    /** A imagem está em Log (false color e decodificação usam a LogCurve) ou em Rec.709. */
    val isLog: Boolean = true,
)

/** Histograma (64 classes) e waveform (128 colunas × 64 níveis) do sinal gravado, de um quadro reduzido. */
class Scopes(val histogram: IntArray, val waveform: IntArray) {
    companion object {
        const val BINS = 64
        const val COLS = 128
        const val ROWS = 72
    }
}

/**
 * Preview em OpenGL ES 3: a câmera escreve numa SurfaceTexture (textura OES), e esta classe
 * desenha num SurfaceView com aspect-fit e rotação, aplicando as ferramentas de monitoramento.
 */
class PreviewRenderer {
    private val thread = HandlerThread("gl").apply { start() }
    private val handler = Handler(thread.looper)

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var viewW = 0
    private var viewH = 0

    private var program = 0
    private var scopeProgram = 0
    private var texId = 0
    private var lutTex = 0
    private var lutSize = 0
    private var scopeFbo = 0
    private var scopeTex = 0
    private val scopePixels: ByteBuffer =
        ByteBuffer.allocateDirect(Scopes.COLS * Scopes.ROWS * 4).order(ByteOrder.nativeOrder())
    private var frameCount = 0L
    private lateinit var texture: SurfaceTexture
    private val texMatrix = FloatArray(16)
    private val mvp = FloatArray(16)
    private val rotOnly = FloatArray(16)

    // Geometria do último quadro desenhado, para converter um toque na tela em coordenadas do buffer.
    @Volatile private var lastGeom: Geom? = null

    private class Geom(val tex: FloatArray, val sx: Float, val sy: Float, val rot: Int, val w: Int, val h: Int)

    /** Tamanho do buffer da câmera (para o aspect-fit e o peaking). */
    @Volatile var inputWidth = 1920
    @Volatile var inputHeight = 1080

    /** Rotação atual da tela (0/90/180/270), atualizada quando a tela gira. */
    @Volatile var displayDegrees = 90

    /** SENSOR_ORIENTATION da câmera em uso. */
    @Volatile var sensorOrientation = 90

    @Volatile var options = MonitorOptions()

    /** Último histograma/waveform (só com [MonitorOptions.scopes] ligado). */
    @Volatile var scopes: Scopes? = null
        private set

    /** Nome da LUT carregada (null = nenhuma). */
    @Volatile var lutName: String? = null
        private set

    val surfaceTexture = CompletableDeferred<SurfaceTexture>()

    // ---- Preview remoto (MJPEG): a mesma imagem da tela, reduzida, a ~10 fps ----
    /** Liga a produção de JPEGs (só enquanto houver alguém assistindo pela web). */
    @Volatile var remoteStreaming = false
    @Volatile private var jpeg: Pair<ByteArray, Long>? = null
    private val jpegThread = HandlerThread("jpeg").apply { start() }
    private val jpegHandler = Handler(jpegThread.looper)
    private val remoteBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    private var remoteFbo = 0
    private var remoteTex = 0
    private var remoteW = 0
    private var remoteH = 0
    private var remotePixels: ByteBuffer? = null
    private var remoteBitmap: android.graphics.Bitmap? = null
    private var jpegCount = 0L
    private val remoteMvp = FloatArray(16)

    /** Último JPEG do preview e o número do quadro. */
    fun latestJpeg(): Pair<ByteArray, Long>? = jpeg

    private val quad: FloatBuffer = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f).toBuffer()
    private val uv: FloatBuffer = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f).toBuffer()

    init {
        handler.post {
            initEgl()
            program = buildProgram(VERTEX, FRAGMENT)
            scopeProgram = buildProgram(VERTEX, SCOPE_FRAGMENT)
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            texId = ids[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            initScopeTarget()
            texture = SurfaceTexture(texId)
            texture.setOnFrameAvailableListener({ draw() }, handler)
            surfaceTexture.complete(texture)
        }
    }

    /**
     * Carrega uma LUT .cube (texto) como textura 3D. Lança erro se o arquivo for inválido.
     * Chamar fora da thread de GL; o upload é feito nela.
     */
    fun setLut(name: String, cubeText: String) {
        val cube = CubeLut.parse(cubeText)
        handler.post {
            if (lutTex == 0) {
                val ids = IntArray(1)
                GLES30.glGenTextures(1, ids, 0)
                lutTex = ids[0]
            }
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
            for (p in intArrayOf(GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_TEXTURE_WRAP_R)) {
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, p, GLES30.GL_CLAMP_TO_EDGE)
            }
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            val buf = ByteBuffer.allocateDirect(cube.data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            buf.put(cube.data).position(0)
            // .cube: R varia mais rápido, depois G, depois B = x, y, z de uma textura 3D.
            GLES30.glTexImage3D(
                GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB16F, cube.size, cube.size, cube.size, 0,
                GLES30.GL_RGB, GLES30.GL_FLOAT, buf,
            )
            lutSize = cube.size
            lutName = name
        }
    }

    /** Liga (ou desliga, com null) a surface do SurfaceView onde o preview é desenhado. */
    fun setOutput(surface: Surface?, width: Int, height: Int) {
        handler.post {
            if (window != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
                EGL14.eglDestroySurface(display, window)
                window = EGL14.EGL_NO_SURFACE
            }
            if (surface != null && surface.isValid) {
                window = EGL14.eglCreateWindowSurface(display, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0)
                viewW = width
                viewH = height
            }
        }
    }

    /**
     * Converte um toque (pixels da view) em coordenadas normalizadas do buffer da câmera
     * (0..1, origem no canto superior esquerdo). null se o toque caiu fora da imagem.
     * Desfaz, na ordem inversa, o que o draw() faz: aspect-fit, rotação e a matriz da SurfaceTexture.
     */
    fun viewToBuffer(x: Float, y: Float): Pair<Float, Float>? {
        val g = lastGeom ?: return null
        if (g.w == 0 || g.h == 0) return null
        // t = 0 é a primeira linha do buffer (topo da imagem); a matriz já inclui o flip vertical.
        return ndcToBuffer(g, (2f * x / g.w - 1f) / g.sx, (1f - 2f * y / g.h) / g.sy)
    }

    fun release() {
        handler.post {
            runCatching { texture.release() }
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
            EGL14.eglDestroySurface(display, pbuffer)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
        thread.quitSafely()
        jpegThread.quitSafely()
    }

    private fun draw() {
        if (window == EGL14.EGL_NO_SURFACE) {
            // Sem tela: ainda precisa consumir o quadro para a câmera não travar.
            EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
            texture.updateTexImage()
            return
        }
        EGL14.eglMakeCurrent(display, window, window, context)
        texture.updateTexImage()
        texture.getTransformMatrix(texMatrix)
        val o = options

        // A Camera2 costuma pôr a rotação do sensor na própria matriz da SurfaceTexture (a imagem já
        // chega "em pé" na orientação natural, retrato). Se a matriz troca os eixos, é esse o caso:
        // falta só compensar a rotação da tela. Senão, a imagem está na orientação do sensor.
        val swapped = kotlin.math.abs(texMatrix[0]) < 0.5f
        val rot = if (swapped) displayDegrees else (displayDegrees - sensorOrientation + 360) % 360
        val cw = if (swapped) inputHeight else inputWidth
        val ch = if (swapped) inputWidth else inputHeight
        // Aspect-fit: encaixa a imagem (girada) dentro da view, com barras pretas.
        val srcAspect = if (rot % 180 == 0) cw.toFloat() / ch else ch.toFloat() / cw
        val viewAspect = viewW.toFloat() / viewH
        val sx = if (srcAspect > viewAspect) 1f else srcAspect / viewAspect
        val sy = if (srcAspect > viewAspect) viewAspect / srcAspect else 1f
        Matrix.setIdentityM(mvp, 0)
        Matrix.scaleM(mvp, 0, sx, sy, 1f)
        Matrix.rotateM(mvp, 0, rot.toFloat(), 0f, 0f, 1f)
        lastGeom = Geom(texMatrix.copyOf(), sx, sy, rot, viewW, viewH)

        // Scopes: a cada 4 quadros, o sinal cru num quadro de 128x72, lido de volta para a CPU.
        if (o.scopes && frameCount++ % 4 == 0L) {
            Matrix.setIdentityM(rotOnly, 0)
            Matrix.rotateM(rotOnly, 0, rot.toFloat(), 0f, 0f, 1f)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, scopeFbo)
            GLES20.glViewport(0, 0, Scopes.COLS, Scopes.ROWS)
            GLES20.glUseProgram(scopeProgram)
            bindCommon(scopeProgram, rotOnly)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            scopePixels.position(0)
            GLES20.glReadPixels(0, 0, Scopes.COLS, Scopes.ROWS, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, scopePixels)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            scopes = computeScopes(scopePixels)
        } else if (!o.scopes) {
            scopes = null
        }

        GLES20.glViewport(0, 0, viewW, viewH)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        bindCommon(program, mvp)
        val lutOn = o.lut && lutTex != 0
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
        u1i(program, "uLut", 1)
        u1f(program, "uLutOn", if (lutOn) 1f else 0f)
        u1f(program, "uLutSize", lutSize.coerceAtLeast(2).toFloat())
        u1f(program, "uIsLog", if (o.isLog) 1f else 0f)
        u1f(program, "uLogA", LogCurve.a.toFloat())
        u1f(program, "uLogC", LogCurve.c.toFloat())
        u1f(program, "uLogD", LogCurve.d.toFloat())
        u1f(program, "uFalseColor", if (o.falseColor) 1f else 0f)
        u1f(program, "uZebra", if (o.zebra) 1f else 0f)
        u1f(program, "uZebraLevel", o.zebraLevel)
        u1f(program, "uPeaking", if (o.peaking) 1f else 0f)
        u1f(program, "uPeakThresh", o.peakingThreshold)
        val pc = o.peakingColor
        GLES20.glUniform3f(
            GLES20.glGetUniformLocation(program, "uPeakColor"),
            (pc shr 16 and 0xFF) / 255f, (pc shr 8 and 0xFF) / 255f, (pc and 0xFF) / 255f,
        )
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uTexel"), 1f / inputWidth, 1f / inputHeight)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // Preview remoto: a cada 3 quadros, a mesma imagem (com LUT/false color...) num FBO pequeno.
        if (remoteStreaming && frameCount % 3 == 1L && remoteBusy.compareAndSet(false, true)) {
            renderRemote(rot, srcAspect)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, viewW, viewH)
        }
        if (!o.scopes) frameCount++
        EGLExt.eglPresentationTimeANDROID(display, window, texture.timestamp)
        EGL14.eglSwapBuffers(display, window)
    }

    /** Desenha no FBO remoto, lê os pixels e manda comprimir em JPEG noutra thread. */
    private fun renderRemote(rot: Int, aspect: Float) {
        val w = if (aspect >= 1f) 640 else 360
        val h = (w / aspect).toInt().coerceIn(16, 1280) and 1.inv()
        if (w != remoteW || h != remoteH) {
            val ids = IntArray(1)
            if (remoteTex == 0) { GLES20.glGenTextures(1, ids, 0); remoteTex = ids[0] }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, remoteTex)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            if (remoteFbo == 0) { GLES20.glGenFramebuffers(1, ids, 0); remoteFbo = ids[0] }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, remoteFbo)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, remoteTex, 0)
            remoteW = w
            remoteH = h
            remotePixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            remoteBitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, remoteFbo)
        GLES20.glViewport(0, 0, w, h)
        // Sem barras (a imagem ocupa o FBO todo) e de cabeça para baixo: o glReadPixels lê de baixo
        // para cima, e o Bitmap espera de cima para baixo.
        Matrix.setIdentityM(remoteMvp, 0)
        Matrix.scaleM(remoteMvp, 0, 1f, -1f, 1f)
        Matrix.rotateM(remoteMvp, 0, rot.toFloat(), 0f, 0f, 1f)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uMvp"), 1, false, remoteMvp, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        val px = remotePixels!!
        px.position(0)
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px)
        val bmp = remoteBitmap!!
        jpegHandler.post {
            try {
                px.position(0)
                bmp.copyPixelsFromBuffer(px)
                val out = java.io.ByteArrayOutputStream(64 * 1024)
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, out)
                jpeg = out.toByteArray() to ++jpegCount
            } catch (_: Throwable) {
            } finally {
                remoteBusy.set(false)
            }
        }
    }

    /**
     * Converte um ponto da imagem remota (u, v em 0..1, origem no canto superior esquerdo, como o JPEG
     * do stream) em coordenadas do buffer da câmera, para o toque para focar pela web.
     */
    fun imageToBuffer(u: Float, v: Float): Pair<Float, Float>? {
        val g = lastGeom ?: return null
        return ndcToBuffer(g, 2f * u - 1f, 1f - 2f * v)
    }

    private fun ndcToBuffer(g: Geom, px: Float, py: Float): Pair<Float, Float>? {
        if (px !in -1f..1f || py !in -1f..1f) return null
        val a = Math.toRadians(-g.rot.toDouble())
        val qx = (px * kotlin.math.cos(a) - py * kotlin.math.sin(a)).toFloat()
        val qy = (px * kotlin.math.sin(a) + py * kotlin.math.cos(a)).toFloat()
        val st = FloatArray(4)
        Matrix.multiplyMV(st, 0, g.tex, 0, floatArrayOf((qx + 1f) / 2f, (qy + 1f) / 2f, 0f, 1f), 0)
        return st[0].coerceIn(0f, 1f) to st[1].coerceIn(0f, 1f)
    }

    private fun bindCommon(p: Int, matrix: FloatArray) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(p, "uMvp"), 1, false, matrix, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(p, "uTex"), 1, false, texMatrix, 0)
        u1i(p, "uImage", 0)
        val aPos = GLES20.glGetAttribLocation(p, "aPos")
        val aUv = GLES20.glGetAttribLocation(p, "aUv")
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glEnableVertexAttribArray(aUv)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 0, uv)
    }

    private fun computeScopes(px: ByteBuffer): Scopes {
        val hist = IntArray(Scopes.BINS)
        val wave = IntArray(Scopes.COLS * Scopes.BINS)
        // O shader de scope já escreve a luma no canal R.
        for (row in 0 until Scopes.ROWS) {
            for (col in 0 until Scopes.COLS) {
                val y = px.get((row * Scopes.COLS + col) * 4).toInt() and 0xFF
                val bin = y * Scopes.BINS / 256
                hist[bin]++
                wave[col * Scopes.BINS + bin]++
            }
        }
        return Scopes(hist, wave)
    }

    private fun initScopeTarget() {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        scopeTex = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, scopeTex)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, Scopes.COLS, Scopes.ROWS, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glGenFramebuffers(1, ids, 0)
        scopeFbo = ids[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, scopeFbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, scopeTex, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun u1f(p: Int, name: String, v: Float) = GLES20.glUniform1f(GLES20.glGetUniformLocation(p, name), v)
    private fun u1i(p: Int, name: String, v: Int) = GLES20.glUniform1i(GLES20.glGetUniformLocation(p, name), v)

    private fun initEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize falhou" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) && num[0] > 0) { "sem EGLConfig" }
        eglConfig = configs[0]
        context = EGL14.eglCreateContext(
            display, eglConfig, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0
        )
        pbuffer = EGL14.eglCreatePbufferSurface(
            display, eglConfig, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
        )
        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
    }

    private fun buildProgram(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src.trimIndent())
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "shader: " + GLES20.glGetShaderInfoLog(s) }
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "link: " + GLES20.glGetProgramInfoLog(p) }
        return p
    }

    private fun FloatArray.toBuffer(): FloatBuffer =
        ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(this@toBuffer); position(0) }

    companion object {
        // #version precisa ser a 1ª linha: os shaders passam por trimIndent().
        private const val VERTEX = """
            #version 300 es
            uniform mat4 uMvp;
            uniform mat4 uTex;
            in vec4 aPos;
            in vec4 aUv;
            out vec2 vUv;
            void main() {
                gl_Position = uMvp * aPos;
                vUv = (uTex * aUv).xy;
            }
        """

        /** Sinal cru → luma no canal R (para histograma/waveform). */
        private const val SCOPE_FRAGMENT = """
            #version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision mediump float;
            uniform samplerExternalOES uImage;
            in vec2 vUv;
            out vec4 outColor;
            void main() {
                vec3 c = texture(uImage, vUv).rgb;
                float y = dot(c, vec3(0.2627, 0.6780, 0.0593));
                outColor = vec4(y, y, y, 1.0);
            }
        """

        private const val FRAGMENT = """
            #version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision highp float;
            precision mediump sampler3D;
            uniform samplerExternalOES uImage;
            uniform sampler3D uLut;
            uniform float uLutOn;
            uniform float uLutSize;
            uniform float uIsLog;
            uniform float uLogA;
            uniform float uLogC;
            uniform float uLogD;
            uniform float uFalseColor;
            uniform float uZebra;
            uniform float uZebraLevel;
            uniform float uPeaking;
            uniform float uPeakThresh;
            uniform vec3 uPeakColor;
            uniform vec2 uTexel;
            in vec2 vUv;
            out vec4 outColor;

            // Luma com os pesos BT.2020 (o Log e gravado em BT.2020); em Rec.709 a diferenca e pequena.
            float luma(vec3 c) { return dot(c, vec3(0.2627, 0.6780, 0.0593)); }

            vec3 applyLut(vec3 c) {
                vec3 s = (clamp(c, 0.0, 1.0) * (uLutSize - 1.0) + 0.5) / uLutSize;
                return texture(uLut, s).rgb;
            }

            // Sinal gravado -> luz linear da cena (0..1). Log: inversa da LogCurve. Normal: inversa da 709.
            float sceneLinear(float y) {
                if (uIsLog > 0.5) return pow(10.0, (y - uLogD) / uLogA) - uLogC;
                return y < 0.081 ? y / 4.5 : pow((y + 0.099) / 1.099, 1.0 / 0.45);
            }

            // Faixas em stops em relacao ao cinza 18%, no estilo das cameras de cinema.
            vec3 falseColor(float y, float shown) {
                float lin = sceneLinear(y);
                float st = log2(max(lin, 1e-5) / 0.18);
                if (lin >= 0.95) return vec3(1.0, 0.0, 0.0);              // estourado
                if (st >= 2.0) return vec3(1.0, 0.9, 0.0);                // realce perto do limite
                if (st >= 0.6 && st <= 1.3) return vec3(1.0, 0.5, 0.75);  // pele clara (~+1 stop)
                if (abs(st) <= 0.25) return vec3(0.1, 0.85, 0.2);         // cinza medio
                if (st <= -6.0) return vec3(0.55, 0.0, 0.75);             // preto / ruido
                if (st <= -4.0) return vec3(0.1, 0.35, 1.0);              // sombra profunda
                return vec3(shown * 0.85);
            }

            void main() {
                vec3 raw = texture(uImage, vUv).rgb;
                float y = luma(raw);
                vec3 shown = uLutOn > 0.5 ? applyLut(raw) : raw;
                vec3 c = shown;
                if (uFalseColor > 0.5) {
                    c = falseColor(y, luma(shown));
                } else if (uZebra > 0.5 && y >= uZebraLevel) {
                    // Listras diagonais sobre o que passa do limite.
                    float band = mod(gl_FragCoord.x + gl_FragCoord.y, 14.0);
                    if (band < 7.0) c = mix(c, vec3(1.0), 0.75); else c = mix(c, vec3(0.0), 0.6);
                }
                if (uPeaking > 0.5) {
                    // Sobel na luma do sinal cru.
                    float tl = luma(texture(uImage, vUv + uTexel * vec2(-1.0, -1.0)).rgb);
                    float t  = luma(texture(uImage, vUv + uTexel * vec2( 0.0, -1.0)).rgb);
                    float tr = luma(texture(uImage, vUv + uTexel * vec2( 1.0, -1.0)).rgb);
                    float l  = luma(texture(uImage, vUv + uTexel * vec2(-1.0,  0.0)).rgb);
                    float r  = luma(texture(uImage, vUv + uTexel * vec2( 1.0,  0.0)).rgb);
                    float bl = luma(texture(uImage, vUv + uTexel * vec2(-1.0,  1.0)).rgb);
                    float b  = luma(texture(uImage, vUv + uTexel * vec2( 0.0,  1.0)).rgb);
                    float br = luma(texture(uImage, vUv + uTexel * vec2( 1.0,  1.0)).rgb);
                    float gx = (tr + 2.0 * r + br) - (tl + 2.0 * l + bl);
                    float gy = (bl + 2.0 * b + br) - (tl + 2.0 * t + tr);
                    if (length(vec2(gx, gy)) > uPeakThresh) c = uPeakColor;
                }
                outColor = vec4(c, 1.0);
            }
        """
    }
}
