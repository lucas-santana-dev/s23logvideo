package br.com.plusapps.s23logvideo.ui

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import br.com.plusapps.s23logvideo.probe.ProbeRunner
import br.com.plusapps.s23logvideo.probe.TestMatrix
import br.com.plusapps.s23logvideo.record.MediaStoreOut
import br.com.plusapps.s23logvideo.record.TestRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Estado e ações da tela Probe. A câmera roda numa HandlerThread própria. */
class ProbeController(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Rotação atual da tela em graus (0, 90, 180, 270). */
    private val displayRotation: () -> Int,
) {
    var busy by mutableStateOf(false)
        private set
    var status by mutableStateOf("Pronto.")
        private set
    val summary = mutableStateListOf<String>()
    val log = mutableStateListOf<String>()

    private val cameraThread = HandlerThread("camera").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)

    fun runProbe() = launchBusy {
        status = "Rodando probe…"
        val root = withContext(Dispatchers.Default) { ProbeRunner.run(context) }
        val path = withContext(Dispatchers.IO) {
            MediaStoreOut.writeReport(context, "probe-${root.getString("geradoEm")}.json", root.toString(2))
        }
        summary.clear()
        summary.addAll(ProbeRunner.summary(root))
        status = "Probe salvo em $path"
    }

    fun runMatrix() = launchBusy {
        val cameraId = TestMatrix.defaultBackCamera(context) ?: error("nenhuma câmera traseira")
        val plan = withContext(Dispatchers.Default) { TestMatrix.plan(context, cameraId) }
        val runTag = ProbeRunner.timestamp()
        val recorder = TestRecorder(context, cameraHandler)
        val hint = (plan.sensorOrientation - displayRotation() + 360) % 360
        val results = JSONArray()
        log.clear()
        log += "Câmera $cameraId · ${plan.clips.count { it.skipReason == null }} clipes de 10 s · " +
            "curva com ${plan.curvePoints} pontos"

        plan.clips.forEachIndexed { i, clip ->
            val label = "[${i + 1}/${plan.clips.size}] ${clip.spec.name}"
            if (clip.skipReason != null) {
                log += "$label: PULADO, ${clip.skipReason}"
                results.put(JSONObject().put("spec", clip.spec.toJson()).put("status", "pulado").put("motivo", clip.skipReason))
                return@forEachIndexed
            }
            status = "Gravando $label…"
            val r = recorder.record(cameraId, clip.spec, clip.fpsRange!!, plan.curvePoints, hint, runTag)
            withContext(Dispatchers.IO) {
                MediaStoreOut.writeReport(context, "${clip.spec.name}_$runTag.json", r.toString(2), "clips")
            }
            results.put(r)
            log += "$label: ${r.optString("veredito")}"
            delay(1_500) // folga para o ISP e a temperatura entre clipes
        }

        val path = withContext(Dispatchers.IO) {
            MediaStoreOut.writeReport(
                context, "matriz-$runTag.json",
                JSONObject().put("runTag", runTag).put("cameraId", cameraId).put("clipes", results).toString(2)
            )
        }
        status = "Matriz concluída. Relatório em $path; vídeos em ${MediaStoreOut.VIDEO_DIR}"
    }

    fun close() {
        cameraThread.quitSafely()
    }

    private fun launchBusy(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
            } catch (t: Throwable) {
                status = "Erro: $t"
            } finally {
                busy = false
            }
        }
    }
}
