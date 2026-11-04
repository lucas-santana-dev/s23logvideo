# Fase 2: Log em 10 bits (tarefas para continuar na branch `fase-2`)

Contexto: `docs/s23-capacidades.md` (Fase 0). No S23, a Camera2 aceita HLG10 (P010, BT.2020) com
`TONEMAP_MODE_CONTRAST_CURVE` em 4K30, e a curva da `color/LogCurve.kt` é aplicada sobre luz linear
(erro de 0,02 a 0,03). A Fase 1 (gravação com áudio, commit `3e3fb38` na `main`) já tem os interruptores
`tenBit` e `curve` no `VideoConfig`.

## Já feito nesta branch
- `record/VideoConfig.kt`: `isLog`, `withLog()`, `withMode()`; em Log, o arquivo é marcado BT.2020 +
  transferência **SDR** (não HLG), para o editor não aplicar tone mapping de HDR; bitrate de 4K30 10 bits
  = 150 Mb/s.

## Falta fazer
1. **`camera/CameraController.kt` → `setRepeating`**: quando `cfg.curve`, pedir `EDGE_MODE_OFF` e
   `NOISE_REDUCTION_MODE_MINIMAL` (os dois existem na câmera 0, ver probe da rodada 2); conferir a
   disponibilidade em `EDGE_AVAILABLE_EDGE_MODES` / `NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES`
   antes de setar.
2. **`ui/RecordScreen.kt` / `ui/CameraViewModel.kt`**:
   - config inicial = `VideoConfig(3840, 2160, 30, tenBit = true, curve = true)` (Log por padrão);
   - chips de modo usando `config.withMode(w, h, fps)` (4K30, 4K24, 1080p30, 1080p24) e um chip
     separado **"LOG 10-bit"** que alterna `config.withLog(!config.isLog)`; tudo desabilitado durante
     a gravação; trocar a config reconfigura a sessão (já existe `selectConfig`).
   - chip **"Monitor 709"** (só quando `isLog`) que liga a conversão no preview (item 3).
3. **`gl/PreviewRenderer.kt`**: modo de monitoramento Log→Rec.709 no shader do preview (só a tela; o
   arquivo continua em Log). Uniforms `uLogA`, `uLogC`, `uLogD` = `LogCurve.a/c/d` e `uToRec709` (0/1):
   - `lin = pow(10.0, (rgb - d) / a) - c` (inversa da LogCurve, por canal);
   - BT.2020 → BT.709 linear (multiplicar pela matriz de linhas
     `[1.6605,-0.5876,-0.0728; -0.1246,1.1329,-0.0083; -0.0182,-0.1006,1.1187]`), `max(0.0)`;
   - rolloff suave acima de 0,8 (ex.: `x > 0.8 ? 0.8 + 0.2*(1-exp(-(x-0.8)/0.2)) : x`);
   - OETF Rec.709 (`x < 0.018 ? 4.5x : 1.099 x^0.45 - 0.099`).
   Expor `@Volatile var monitor709` no renderer; o ViewModel liga/desliga.
4. **`tools/make_lut.py`**: gera `luts/LogCam_to_Rec709.cube` (33³, `LUT_3D_SIZE 33`, entrada 0–1)
   com a MESMA conversão do item 3. Ler as constantes `GRAY_IN`, `GRAY_OUT`, `BLACK_OUT` e `WHITE_OUT`
   de `app/src/main/java/.../color/LogCurve.kt` por regex (fonte única) e resolver `c` por bisseção como
   no `init` da `LogCurve`. Commitar o `.cube` gerado.
5. **`docs/fase2.md`**: como usar (chips, monitor 709), como aplicar a LUT no DaVinci (1º nó, projeto
   em DaVinci YRGB sem gerenciamento de cor) e no CapCut, e o checklist de teste:
   - `ffprobe`: `yuv420p10le`, `Main 10`, `color_primaries=bt2020`, `color_transfer=bt709` (não
     `arib-std-b67`);
   - relatório em `Download/LogCam/gravacoes/*.json`: `tonemapModeDevolvido` = `CONTRAST_CURVE`;
   - imagem lavada no arquivo e normal com a LUT;
   - pendências: o teste de 10 min, o estalo (`tools/av_sync.py`), uma cena com janela/céu (realces) e o
     preto com a lente tampada.

## Regras
- Trabalhar só na branch `fase-2`; não mexer na `main`.
- Compilar com `./gradlew assembleDebug` se o ambiente tiver Android SDK; se não tiver, dizer isso
  claramente no commit/PR e revisar o código com cuidado (Kotlin 2.0.21, AGP 8.13.2, minSdk 33).
- Commits pequenos, em português, terminando com
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Ao terminar: push da `fase-2` e um PR **rascunho** para a `main` descrevendo o que foi feito e o
  que falta testar no aparelho.
