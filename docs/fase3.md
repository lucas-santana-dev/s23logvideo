# Fase 3: controles manuais

Em Log, nada pode mudar sozinho no meio do take: qualquer ajuste automático aparece depois na correção
de cor. A Fase 3 põe exposição, balanço de branco e foco sob controle, numa barra de botões grandes
embaixo da tela (operável com o polegar), com uma "roda" de valores para cada ajuste.

## A barra

| Botão | Toque abre | Observação |
|---|---|---|
| **EXP** | AUTO · TRAVAR · MANUAL | TRAVAR = mede no automático e trava (`CONTROL_AE_LOCK`). MANUAL = `AE_MODE_OFF` + ISO + obturador. Ao entrar em MANUAL, o app parte do ISO/obturador que o automático estava usando. |
| **ISO** | roda 50 … 3200 (1/3 de stop) | Tocar um valor já liga o MANUAL. Faixa filtrada pelo `SENSOR_INFO_SENSITIVITY_RANGE`. |
| **OBT** | roda 1/30 … 1/4000 + botão **180°** | Só aparecem obturadores que cabem no quadro (não derrubam o fps). 180° = 1/(2×fps): 1/60 em 30 fps, 1/48 em 24 fps. Em luz de rede 60 Hz (Brasil) prefira 1/60 ou 1/120 para não piscar. |
| **WB** | AUTO · TRAVAR · KELVIN + roda 2300–10000 K | KELVIN converte a temperatura em `COLOR_CORRECTION_GAINS` pela calibração do sensor (método do DNG, ver abaixo). TRAVAR = `CONTROL_AWB_LOCK`. |
| **FOCO** | slider ∞ ↔ perto, AF, Travar, Marcar A/B, → A / → B | Some se a lente for de foco fixo. → A/→ B fazem uma puxada de foco suave (1,5 s, ease-in-out). |
| **PRESET** | lista + "Salvar atual" | Presets salvos no aparelho. Vêm três de partida: "Externa dia", "Igreja interna" e "Automático". × apaga. |

Em AUTO, ISO/OBT/FOCO mostram em **cinza** o que a câmera está medindo; travado ou manual, em
**amarelo**. Os ajustes valem também durante a gravação (dá para puxar foco no meio do take) e ficam
salvos para a próxima vez que o app abrir.

**Toque na imagem** para focar num ponto: o app dispara um AF único naquela região
(`CONTROL_AF_REGIONS` + `AF_TRIGGER_START`) e, quando o foco trava, passa o FOCO para MANUAL naquela
distância. Com uma roda aberta, o primeiro toque na imagem só fecha a roda.

## Detalhes técnicos

- Exposição MANUAL fixa `SENSOR_FRAME_DURATION` = 1/fps; o obturador é limitado a esse valor.
- Kelvin → ganhos: a cor neutra no espaço do sensor é `CM · XYZ(xy)`, com xy no locus de Planck
  (Kim et al.) e CM = `SENSOR_CALIBRATION_TRANSFORMn × SENSOR_COLOR_TRANSFORMn`, interpolada em 1/CCT
  entre os dois iluminantes de referência. Os ganhos são o inverso da cor neutra (menor = 1). A matriz
  de cor (`COLOR_CORRECTION_TRANSFORM`) é a última que o AWB automático usou. Se o sensor não tiver
  calibração, o app avisa e usa uma aproximação relativa aos ganhos do AWB.
- O relatório JSON de cada gravação (`Download/LogCam/gravacoes`) ganhou o bloco `ajustes`, com os
  ajustes e o ISO/obturador/foco medidos no início do take.
- Presets em SharedPreferences (o plano falava em DataStore; SharedPreferences evita uma dependência
  nova e basta para meia dúzia de presets).

## Testar no S23

- [ ] EXP MANUAL: ISO e obturador mudam a imagem e o `ajustes` do JSON bate; fps continua 29,9.
- [ ] OBT: com 30 fps não aparece nada mais lento que 1/30; 180° dá 1/60.
- [ ] Lâmpada da casa com 1/50 deve piscar (rede 60 Hz); com 1/60 não.
- [ ] WB KELVIN: 3200 K numa lâmpada amarela deixa branco neutro; 5600 K de dia idem. Se as cores
      saírem claramente erradas (ex.: tudo verde), anotar: a conversão Kelvin precisa de ajuste.
- [ ] WB TRAVAR e EXP TRAVAR: apontar para outra cena e ver que não mudam.
- [ ] Toque para focar perto e longe; o quadradinho aparece **onde se tocou** e o foco vai para lá
      (se for para o ponto espelhado, o mapeamento do toque precisa de ajuste).
- [ ] Marcar A (perto), Marcar B (longe), → A / → B durante uma gravação: puxada suave, sem saltos.
- [ ] Salvar um preset, fechar o app, abrir e aplicar.
