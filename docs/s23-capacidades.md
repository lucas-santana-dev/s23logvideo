# O que o meu S23 aceita (Fase 0)

Refazer após cada atualização do One UI. JSONs brutos em `docs/probe/rodada1` e `docs/probe/rodada2`; a rodada 2 é a que vale.

| Campo | Valor |
|---|---|
| Data do probe | 2026-11-04 |
| Aparelho | SM-S911B |
| Build (`Build.DISPLAY`) | BP4A.251205.006.S911BXXSAFZI1 |
| Android | 16 |

## Câmeras (Camera2, app de terceiros)

| ID | Lente | Tipo | Nível | 10 bits | CONTRAST_CURVE | MANUAL_SENSOR | Maior saída p/ encoder (16:9) |
|---|---|---|---|---|---|---|---|
| 0 | Traseira 5,4 mm (principal) | lógica, físicas 2, 5, 6 | LEVEL_3 | HLG10 | sim, máx. 64 pts | sim | 3840x2160@30 |
| 5 | Traseira 5,4 mm (principal) | física de 0 | LEVEL_3 | HLG10 | sim, 64 pts | sim | 3840x2160@30 |
| 2 | Traseira 2,2 mm (ultrawide) | única (também física de 0) | LIMITED | HLG10 | sim, 64 pts | sim | 3840x2160@30 |
| 6 | Traseira 7,0 mm (tele) | física de 0 | LIMITED | HLG10 | sim, 64 pts | sim | **1920x1080@30** |
| 1 | Frontal 3,3 mm | única | FULL | não | sim | sim | 3840x2160@30 |
| 3 | Frontal 3,3 mm | única | FULL | não | sim | sim | 1920x1080@30 |

- Faixas de fps do AE na principal: 10, 15, 24, 26, 27 e 30 fixos, mais 7–26, 7–27 e 7–30. **Não há 25 fps fixo.**
- Alta velocidade (sessão constrained): 1280x720, 1920x1080 e 1920x824.
- ISO anunciado: 50–3200. O CaptureResult chegou a 11865 em cena escura (ganho digital acima da faixa anunciada).
- Nenhuma chave `samsung.*` aparece nas request keys públicas.

## Encoder HEVC

| Encoder | Main10 | 4K30 | 4K60 | 8K30 | Bitrate máx. |
|---|---|---|---|---|---|
| c2.qti.hevc.encoder (hw) | sim | sim | sim | sim | 160 Mb/s |
| c2.qti.hevc.encoder.hdr (hw) | sim | sim | sim | não | 160 Mb/s |
| c2.qti.hevc.encoder.cq (hw) | sim | não | não | não | 160 Mb/s |
| c2.android.hevc.encoder (sw) | não | não | não | não | 10 Mb/s |

O encoder aguenta 4K60 e 8K30; quem limita é a câmera, que não oferece esses modos para MediaCodec.

## Matriz de testes, rodada 2 (câmera 0, mesma cena, ISO ~395, 1/60)

| Clipe | Status | fps médio | Perdidos | TONEMAP_MODE devolvido | pix_fmt | transfer (tag) | Mb/s |
|---|---|---|---|---|---|---|---|
| 4K30_8bit_padrao | ok | 29,9 | 1 | FAST | yuvj420p | bt709 | 100,6 |
| 4K30_8bit_curva | ok | 29,9 | 1 | CONTRAST_CURVE (64 pts) | yuvj420p | bt709 | 101,9 |
| 4K30_10bit_padrao | ok | 29,9 | 1 | FAST | yuv420p10le | arib-std-b67 (HLG) | 99,7 |
| 4K30_10bit_curva | ok | 29,9 | 1 | CONTRAST_CURVE (64 pts) | yuv420p10le | arib-std-b67 (HLG) | 99,0 |
| 4K60 (todos) | pulado | | | | | | câmera limita 4K a 30 fps |
| 8K30 (todos) | pulado | | | | | | câmera não oferece 7680x4320 |

### A curva é aplicada de verdade? (`tools/curve_fit.py`)

Casando os quantis de luma do clipe padrão com os do clipe com curva (mesma cena e mesma exposição), em 10 bits:

| Luz linear estimada | Esperado pela `LogCurve` | Medido no clipe com curva |
|---|---|---|
| 0,004 | 0,104 | 0,104 |
| 0,055 | 0,217 | 0,196 |
| 0,135 | 0,350 | 0,333 |
| 0,214 | 0,450 | 0,436 |
| 0,286 | 0,526 | 0,501 |

O ISP aplica a curva sobre luz linear, com erro de 0,02 a 0,03; só o preto mais profundo fica abaixo (0,044 contra 0,093). Em 8 bits ela também é aplicada, mas com desvio maior, porque a curva padrão da Samsung não é sRGB pura.

Rodada 1: os clipes de 10 bits saíram pretos (lente coberta ou celular movido) e a curva usava pontos densos nas sombras. Não é conclusiva; a rodada 2 foi feita com pontos igualmente espaçados.

## Decisão

**Estratégia A (curva no ISP, em 10 bits HLG10).** O `CONTRAST_CURVE` é aceito junto com o HLG10, o arquivo sai em Main 10 `yuv420p10le` e a imagem segue a curva pedida.

Pendências para a Fase 2:
- Gravar o arquivo **sem** a tag HLG: hoje ele sai marcado `arib-std-b67`, e o editor aplicaria tone mapping de HDR em cima do Log.
- Limite de 64 pontos: definir a curva final com pontos igualmente espaçados (é o que foi validado).
- Testar com uma cena de alto contraste (céu/janela), porque esta cena só chegou a ~0,29 de luz linear e os realces não foram medidos.
- Conferir o trecho do preto (0,044 medido contra 0,093 esperado), com tampa na lente.
- Teto de trabalho: **4K a 24 ou 30 fps**, na principal ou na ultrawide; a tele só em 1080p.
