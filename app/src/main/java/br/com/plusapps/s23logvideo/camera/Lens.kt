package br.com.plusapps.s23logvideo.camera

/**
 * As três traseiras do S23 (IDs do probe da Fase 0, `docs/s23-capacidades.md`):
 *  - principal: câmera lógica 0 (física 5), 4K30;
 *  - ultrawide: câmera 2, que o Android também expõe sozinha, 4K30;
 *  - tele: só existe como física (6) da lógica 0, e só até 1080p30.
 * Abrir a câmera direto (ou o stream físico) evita o zoom digital da câmera lógica.
 */
enum class Lens(val label: String, val openId: String, val physicalId: String?, val maxWidth: Int) {
    ULTRAWIDE("0.6x", "2", null, 3840),
    MAIN("1x", "0", null, 3840),
    TELE("3x", "0", "6", 1920);

    /** ID cujas características valem para ISO, foco etc. */
    val characteristicsId get() = physicalId ?: openId
}
