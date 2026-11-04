# Estabilização de vídeo

Botão **ESTAB.** na barra de baixo (e na página do controle remoto). Trocar reabre a câmera; não dá durante a
gravação. A escolha fica salva.

| Modo | O que faz | Corte |
|---|---|---|
| **Nenhuma** | nada (tripé, ou estabilizar depois no Gyroflow com o `.gcsv`) | não |
| **Só OIS** (padrão) | estabilização óptica da lente | não; segura tremor pequeno, não segura caminhada |
| **OIS + eletrônica** | `CONTROL_VIDEO_STABILIZATION_MODE_ON` | sim, ~4% medido (indicativo) |
| **OIS + Super (preview)** | `PREVIEW_STABILIZATION` (Android 13+), o mais próximo do modo da câmera da Samsung | sim, ~5,5% medido (indicativo) |

## O que foi medido no S23 (2026-11-04)

Aceitar o modo não significa que ele funciona. Em cada combinação gravei 4 s e olhei o bitrate e o arquivo:

| Configuração | Eletrônica e Super |
|---|---|
| 4K ou 1080p **Normal (8 bits)** | funcionam (100 / 46 Mb/s) |
| 4K ou 1080p **Log (10 bits, HLG10)** | **a câmera aceita, devolve "ON" no resultado e grava quadros vazios** (vídeo verde, 0,03–0,08 Mb/s) |

Por isso o app **só oferece Nenhuma e OIS quando o Log está ligado**. Se a eletrônica estiver escolhida e você
ligar o Log, o app volta para OIS e avisa na tela. Para estabilizar o Log: Gyroflow com o `.gcsv` de cada take.

Rede de segurança: se uma gravação terminar com menos de 2 Mb/s (4K de verdade nunca fica tão baixo), o resumo do
take mostra "ATENÇÃO: o vídeo parece vazio". Pode ser falso alarme com lente tampada ou imagem toda branca/preta.

O relatório JSON de cada take registra `estabilizacaoPedida` e, em `camera`, `estabilizacaoVideoDevolvida` e
`estabilizacaoOpticaDevolvida` (o que a câmera disse ter aplicado). O corte NÃO aparece em `cropRegionFinal`
(o Android informa o sensor inteiro nos quatro modos): o corte é interno. O preview mostra o corte real.

Corte medido comparando o mesmo cenário em 4K Normal: EIS ~96% e Super ~94,5% do campo de visão sem estabilização.
A correlação entre os quadros ficou baixa (a cena se mexeu entre os takes), então é só indicativo.

## Testar andando

- [ ] 4K Normal + Super: andar com o celular na mão e comparar com Só OIS.
- [ ] Ver o corte no preview ao trocar de modo.
- [ ] Em Log: confirmar que só aparecem Nenhuma e OIS.
