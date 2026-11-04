package br.com.plusapps.s23logvideo.camera

import android.hardware.camera2.CameraMetadata

/**
 * Estabilização de vídeo. O OIS (óptico) move a lente e não corta a imagem. A estabilização eletrônica lê o
 * giroscópio e desliza uma janela dentro de uma área maior do sensor, então o quadro final é um recorte:
 * o campo de visão fica menor (quanto mais forte o modo, maior o corte).
 *
 *  - [NONE]: nada (útil em tripé, ou para estabilizar depois no Gyroflow com o .gcsv);
 *  - [OIS]: só a óptica (o padrão até a Fase 5: sem corte, segura tremor pequeno, não segura uma caminhada);
 *  - [EIS]: OIS + eletrônica padrão do Camera2 (`CONTROL_VIDEO_STABILIZATION_MODE_ON`);
 *  - [PREVIEW]: OIS + `PREVIEW_STABILIZATION` (Android 13+), o modo mais próximo do da câmera da Samsung; o preview
 *    mostra o mesmo corte que o vídeo.
 */
enum class Stabilization(val label: String, val short: String, val ois: Boolean, val eisMode: Int) {
    NONE("Nenhuma", "NENH.", false, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF),
    OIS("Só OIS (sem corte)", "OIS", true, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF),
    EIS("OIS + eletrônica", "ELETR.", true, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON),
    PREVIEW("OIS + Super (preview)", "SUPER", true, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION);

    companion object {
        fun fromName(name: String?): Stabilization = entries.firstOrNull { it.name == name } ?: OIS
    }
}
