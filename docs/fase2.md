# Fase 2: Log em 10 bits

O app grava por padrão em **Log 10 bits**: sessão HLG10 (P010, BT.2020), curva da `color/LogCurve.kt`
aplicada pelo ISP (`TONEMAP_MODE_CONTRAST_CURVE`), HEVC Main 10 a 150 Mb/s em 4K30. O arquivo é marcado
BT.2020 com transferência **não especificada** (`unknown`, a mesma convenção do Apple Log), e não HLG nem
`bt709`, para o editor não aplicar tone mapping de HDR e não tratar o arquivo como Rec.709. Não existe código
padrão para a curva do LogCam; o que declara "isto é Log" são os **metadados** gravados em cada take
(`com.logcam.logprofile`, `com.logcam.curve`, `com.logcam.gamut`, `com.logcam.lut`), no mesmo formato do
`com.apple.proapps.logprofile`. Takes antigos: `python tools/marcar_log.py clipe.mp4`
em cima do Log. Em Log o app também pede `EDGE_MODE_OFF` e `NOISE_REDUCTION_MODE_MINIMAL` (quando a
câmera anuncia esses modos), para o ISP não afiar nem alisar a imagem.

## Como usar

Na linha de chips do topo:

| Chip | O que faz |
|---|---|
| 4K30 / 4K24 / 1080p30 / 1080p24 | Resolução e fps. Mantém o Log ou o Normal atual. |
| **LOG 10-bit** | Liga: Log 10 bits (padrão). Desliga: Normal 8 bits com a curva da Samsung. |
| **Monitor 709** | Só aparece em Log. Converte o **preview** para Rec.709 para você enxergar a imagem "normal". O arquivo continua em Log. |

Tudo fica desabilitado durante a gravação. Trocar de modo ou de Log/Normal reconfigura a sessão da
câmera (leva um instante).

O Monitor 709 usa exatamente a mesma conta da LUT abaixo: inversa da LogCurve → matriz BT.2020→BT.709
em luz linear → rolloff suave dos realces acima de 0,8 → OETF Rec.709.

## A LUT

`luts/LogCam_to_Rec709.cube` (33³, entrada 0–1). Gerada por:

```
python tools/make_lut.py
```

O script lê as constantes `GRAY_IN`, `GRAY_OUT`, `BLACK_OUT` e `WHITE_OUT` direto da `LogCurve.kt`.
**Se a curva mudar, rode o script de novo e commite a LUT nova** (os arquivos antigos deixam de bater).

Valores atuais: `y = 1,011514·log10(x + 0,170173) + 0,870964`; cinza 18% grava em 0,41 e sai em 0,409
depois da LUT (o valor Rec.709 do cinza 18%).

### DaVinci Resolve

1. Projeto em **DaVinci YRGB**, **sem** gerenciamento de cor (Project Settings → Color Management →
   Color science = DaVinci YRGB; Timeline color space = Rec.709 Gamma 2.4 ou Rec.709-A, tanto faz para
   a LUT). Não use DaVinci YRGB Color Managed nem ACES: eles tentariam interpretar o arquivo pela tag.
2. Copie o `.cube` para a pasta de LUTs (Project Settings → Color Management → Lookup Tables → Open
   LUT Folder) e clique em **Update Lists**.
3. Na página Color, no **primeiro nó** do clipe: botão direito → LUT → `LogCam_to_Rec709`.
4. Corrija exposição/balanço **antes** da LUT (num nó anterior, ou no mesmo nó com o LUT aplicado
   depois do ajuste) e o look criativo **depois** dela.

Se o clipe aparecer escurecido/"HDR", confira em Clip Attributes que o input não está como HLG.

### CapCut (celular ou desktop)

1. Importe o vídeo e o `.cube` (no celular: copie o arquivo para o aparelho; no CapCut: Ajustar →
   LUT → Importar).
2. Aplique a LUT ao clipe com intensidade 100%.
3. Faça os ajustes (exposição, temperatura) **depois** da LUT, com cuidado: o CapCut aplica a LUT em
   8 bits, então ajustes fortes podem gerar banding.

O CapCut pode ignorar a tag BT.2020; como a LUT faz a conversão de gamut, isso não importa.

## Checklist de teste no S23

- [ ] `ffprobe -v error -show_streams arquivo.mp4` no vídeo em Log:
  - `pix_fmt=yuv420p10le`
  - `profile=Main 10`
  - `color_primaries=bt2020`
  - `color_transfer=unknown` (**não** `arib-std-b67` nem `bt709`) e, em `ffprobe -show_entries format_tags`,
    as quatro chaves `com.logcam.*`; o JSON do take traz `marcadoComoLog: true`
- [ ] Relatório em `Download/LogCam/gravacoes/*.json`: `tonemapModeDevolvido` = `CONTRAST_CURVE`.
- [ ] Imagem **lavada** no arquivo e **normal** com a LUT (no Resolve e no CapCut).
- [ ] Monitor 709 no preview parecido com o arquivo + LUT.
- [ ] Desligar o LOG 10-bit: grava 8 bits `yuvj420p`/`bt709` normal, e o chip Monitor 709 some.
- [ ] Chips todos desabilitados durante a gravação.

Pendências (ainda não medidas):

- [ ] Take de **10 minutos** em 4K30 Log (quadros perdidos, temperatura, tamanho do arquivo).
- [ ] **Estalo de dedos** para sincronia áudio/vídeo: `python tools/av_sync.py arquivo.mp4`.
- [ ] Cena de **alto contraste** (janela/céu) para ver os realces e o rolloff.
- [ ] **Preto com a lente tampada**: medido 0,044 na Fase 0 contra 0,093 esperado.
