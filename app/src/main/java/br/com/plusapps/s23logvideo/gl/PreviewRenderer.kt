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
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import br.com.plusapps.s23logvideo.color.LogCurve
import kotlinx.coroutines.CompletableDeferred
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Preview em OpenGL: a câmera escreve numa SurfaceTexture (textura OES), e esta classe
 * desenha num SurfaceView com aspect-fit e rotação. Na Fase 4 é aqui que entram LUT,
 * false color, zebra e peaking. Na Fase 2 o shader pode converter Log → Rec.709 só para a tela
 * ([monitor709]); o arquivo gravado continua em Log.
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
    private var texId = 0
    private lateinit var texture: SurfaceTexture
    private val texMatrix = FloatArray(16)
    private val mvp = FloatArray(16)

    // Geometria do último quadro desenhado, para converter um toque na tela em coordenadas do buffer.
    @Volatile private var lastGeom: Geom? = null

    private class Geom(val tex: FloatArray, val sx: Float, val sy: Float, val rot: Int, val w: Int, val h: Int)

    /** Tamanho do buffer da câmera (para o aspect-fit). */
    @Volatile var inputWidth = 1920
    @Volatile var inputHeight = 1080

    /** Rotação atual da tela (0/90/180/270), atualizada quando a tela gira. */
    @Volatile var displayDegrees = 90

    /** SENSOR_ORIENTATION da câmera em uso. */
    @Volatile var sensorOrientation = 90

    /**
     * Monitoramento Log → Rec.709 no preview (só a tela; o arquivo continua em Log).
     * Mesma conversão de `tools/make_lut.py`. Só faz sentido com a imagem em Log.
     */
    @Volatile var monitor709 = false

    val surfaceTexture = CompletableDeferred<SurfaceTexture>()

    private val quad: FloatBuffer = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f).toBuffer()
    private val uv: FloatBuffer = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f).toBuffer()

    init {
        handler.post {
            initEgl()
            program = buildProgram(VERTEX, FRAGMENT)
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            texId = ids[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            texture = SurfaceTexture(texId)
            texture.setOnFrameAvailableListener({ draw() }, handler)
            surfaceTexture.complete(texture)
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
        val px = (2f * x / g.w - 1f) / g.sx
        val py = (1f - 2f * y / g.h) / g.sy
        if (px !in -1f..1f || py !in -1f..1f) return null
        val a = Math.toRadians(-g.rot.toDouble())
        val qx = (px * kotlin.math.cos(a) - py * kotlin.math.sin(a)).toFloat()
        val qy = (px * kotlin.math.sin(a) + py * kotlin.math.cos(a)).toFloat()
        val u = (qx + 1f) / 2f
        val v = (qy + 1f) / 2f
        val st = FloatArray(4)
        Matrix.multiplyMV(st, 0, g.tex, 0, floatArrayOf(u, v, 0f, 1f), 0)
        // t = 0 é a primeira linha do buffer (topo da imagem); a matriz já inclui o flip vertical.
        return st[0].coerceIn(0f, 1f) to st[1].coerceIn(0f, 1f)
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

        GLES20.glViewport(0, 0, viewW, viewH)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uMvp"), 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTex"), 1, false, texMatrix, 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uImage"), 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uLogA"), LogCurve.a.toFloat())
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uLogC"), LogCurve.c.toFloat())
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uLogD"), LogCurve.d.toFloat())
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uToRec709"), if (monitor709) 1f else 0f)
        val aPos = GLES20.glGetAttribLocation(program, "aPos")
        val aUv = GLES20.glGetAttribLocation(program, "aUv")
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glEnableVertexAttribArray(aUv)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 0, uv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        EGLExt.eglPresentationTimeANDROID(display, window, texture.timestamp)
        EGL14.eglSwapBuffers(display, window)
    }

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
            GLES20.glShaderSource(s, src)
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
        return p
    }

    private fun FloatArray.toBuffer(): FloatBuffer =
        ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(this@toBuffer); position(0) }

    companion object {
        private const val VERTEX = """
            uniform mat4 uMvp;
            uniform mat4 uTex;
            attribute vec4 aPos;
            attribute vec4 aUv;
            varying vec2 vUv;
            void main() {
                gl_Position = uMvp * aPos;
                vUv = (uTex * aUv).xy;
            }
        """
        private const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision highp float;
            uniform samplerExternalOES uImage;
            uniform float uLogA;
            uniform float uLogC;
            uniform float uLogD;
            uniform float uToRec709;
            varying vec2 vUv;

            // BT.2020 -> BT.709 em luz linear. GLSL monta mat3 por colunas: estas são as
            // COLUNAS da matriz de linhas [1.6605,-0.5876,-0.0728; -0.1246,1.1329,-0.0083; -0.0182,-0.1006,1.1187].
            const mat3 BT2020_TO_709 = mat3(
                1.6605, -0.1246, -0.0182,
                -0.5876, 1.1329, -0.1006,
                -0.0728, -0.0083, 1.1187
            );

            float rolloff(float x) {
                return x > 0.8 ? 0.8 + 0.2 * (1.0 - exp(-(x - 0.8) / 0.2)) : x;
            }

            float oetf709(float x) {
                return x < 0.018 ? 4.5 * x : 1.099 * pow(x, 0.45) - 0.099;
            }

            void main() {
                vec4 c = texture2D(uImage, vUv);
                if (uToRec709 > 0.5) {
                    // Inversa da LogCurve: y = a*log10(x + c) + d  ->  x = 10^((y - d)/a) - c
                    vec3 lin = pow(vec3(10.0), (c.rgb - vec3(uLogD)) / uLogA) - vec3(uLogC);
                    lin = max(BT2020_TO_709 * lin, vec3(0.0));
                    lin = vec3(rolloff(lin.r), rolloff(lin.g), rolloff(lin.b));
                    c.rgb = vec3(oetf709(lin.r), oetf709(lin.g), oetf709(lin.b));
                }
                gl_FragColor = c;
            }
        """
    }
}
