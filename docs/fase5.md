# Fase 5: usar todo o poder do S23

O teto realista, confirmado no probe da Fase 0, é **4K30 em 10 bits** (principal e ultrawide) e
**1080p30** na tele. A câmera não oferece 4K60 nem 8K30 para apps de terceiros (o encoder aguentaria),
e alta velocidade (120 fps) só existe em 720p/1080p numa sessão especial, sem perfil de 10 bits.
Esses modos ficam de fora.

## Lentes (chips 0.6x · 1x · 3x)

| Chip | Como abre | Teto |
|---|---|---|
| 0.6x | câmera 2 direto (o Android a expõe sozinha) | 4K30 |
| 1x | câmera lógica 0 | 4K30 |
| 3x | câmera lógica 0 com os dois streams no **sensor físico 6** (`OutputConfiguration.setPhysicalCameraId`) | 1080p30 |

Nada de zoom digital da câmera lógica. A mesma curva Log vai para as três. Faixas de ISO, obturador e
foco são lidas da lente em uso (as rodas mudam). Ao escolher a tele em 4K, o modo desce para 1080p.
Trocar de lente reabre a câmera. Se as cores das lentes divergirem depois da LUT, a solução é uma
LUT por lente (`tools/make_lut.py` aceita `--out`; ver Fase 6).

## Estabilização

- **OIS sempre ligado** onde a lente tem; **EIS desligado** (ele corta a imagem).
- **Giroscópio para a pós** (chip **Giro**, ligado por padrão): durante o take, giroscópio (não
  calibrado, quando existe) + acelerômetro a ~400 Hz vão para `Download/LogCam/gravacoes/<take>.gcsv`,
  no formato de log do **Gyroflow** (`GYROFLOW IMU LOG`, versão 1.3). Os tempos são convertidos para o
  relógio dos quadros e começam no 1º quadro do MP4. O cabeçalho leva `frame_readout_time`, o tempo de
  leitura do rolling shutter medido pela câmera (`SENSOR_ROLLING_SHUTTER_SKEW`).

  No Gyroflow: abrir o MP4, depois **Motion data → Open file** e escolher o `.gcsv`. O S23 não tem
  perfil de lente pronto: calibrar uma vez com o tabuleiro do Gyroflow (ou começar sem perfil). Se a
  correção sair nos eixos errados, ajustar a orientação do IMU no Gyroflow (o arquivo usa os eixos do
  Android em retrato; o celular grava deitado, então a orientação certa costuma ser uma rotação de
  90°, como `YxZ`). Anotar a que funcionar, para fixar no app.

## Áudio (botão ÁUDIO na barra)

- AudioRecord 48 kHz estéreo com a fonte **UNPROCESSED** quando o aparelho a anuncia
  (`PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED`), senão CAMCORDER. AGC e supressor de ruído são desligados
  quando o sistema os anexa.
- **Ganho manual** de −12 a +24 dB (degraus de 3 dB), aplicado no PCM com saturação. Mire os picos da
  voz perto de −12 dBFS no VU.
- **Microfone**: Automático, Interno, ou um **USB-C** / com fio / Bluetooth que estiver conectado (a lista
  é atualizada ao abrir o painel). Usa `AudioRecord.setPreferredDevice`.
- O JSON do take registra a entrada usada (`audio.entrada`).

## Bitrate

4K 10 bits passa a **160 Mb/s**, o máximo do HEVC do S23 (medido na Fase 0). O `VideoEncoder` limita
ao teto anunciado pelo codec, e o JSON registra o valor usado (`bitrateDoEncoder`). Grava no
armazenamento interno (DCIM/LogCam).

## Térmica

- HUD: estado térmico (de "leve" para cima) e aquecimento em % (`getThermalHeadroom`, a partir de 70%).
- Aviso "Aparelho quente" em SEVERE ou com o aquecimento ≥ 90%.
- Em **CRITICAL**, o app para o take sozinho e fecha o arquivo (melhor do que o Android derrubar a
  câmera no meio).
- Durante a gravação, `Window.setSustainedPerformanceMode(true)` quando o aparelho suporta.
- O JSON do take tem `termico.amostras`: estado e aquecimento a cada ~1 s.

## Preview leve

O preview é 1080p (não 4K) e os scopes leem só 128×72 a cada 4 quadros. Peaking e false color são as
ferramentas mais caras; se `quadrosPerdidos` subir com elas ligadas, desligue-as durante takes longos.

## Testar no S23

- [ ] 0.6x / 1x / 3x abrem; a 3x força 1080p e a imagem é óptica (nítida), não um recorte da 1x.
- [ ] Log + LUT parecidos nas três lentes; anotar se alguma puxa de cor.
- [ ] `.gcsv` aparece ao lado do JSON; abre no Gyroflow e estabiliza (testar a orientação).
- [ ] Áudio: "Sem processamento" ligado; falar baixo e alto e ver que o nível não "respira" (sem AGC).
- [ ] Ganho +12 dB sobe o VU; um mic USB-C aparece na lista e grava (`audio.entrada` no JSON).
- [ ] `bitrateDoEncoder` = 160000000 e `bitrateRealMbps` perto de 160 numa cena com detalhe.
- [ ] **Take de 10 min** em 4K30 Log: `termico.amostras` mostra a curva; nenhum quadro perdido em série.
