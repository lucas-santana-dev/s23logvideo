# Fase 4: ferramentas de monitoramento

O Log é cinza na tela. Para expor certo, o app tem estas ferramentas, que rodam **só no preview**: o
arquivo gravado nunca passa pelo shader.

| Chip (linha de cima) | O que mostra |
|---|---|
| **LUT 709** | Aplica a LUT 3D (33³, textura 3D no shader) ao preview. É o **mesmo `.cube`** de `luts/`, empacotado nos assets: o que se vê no app é o que se vê no editor com a LUT. Só em Log. |
| **False color** | Pinta faixas de exposição, em stops em relação ao cinza 18% (calculados com a inversa da `LogCurve`): **verde** = cinza 18% (±¼ stop), **rosa** = +0,6 a +1,3 stop (onde costuma ficar a pele clara; pele mais escura fica perto do verde ou um pouco abaixo), **amarelo** = ≥ +2 stops, **vermelho** = estourado, **azul** = ≤ −4, **roxo** = ≤ −6 (preto/ruído). O resto fica em cinza. É o guia mais confiável em Log. |
| **Zebra** | Listras diagonais onde o sinal **gravado** passa do limite (padrão 90%; em Log o branco do sensor fica em ~94%). |
| **Peaking** | Bordas em foco (Sobel na luma) pintadas numa cor. |
| **Scopes** | Waveform (luma × posição horizontal) e histograma do sinal gravado. Linha branca/verde = cinza 18% (0,41 em Log); linha vermelha = 95%. |
| **⚙** | LUT usada (todas as `.cube` dos assets), nível da zebra (70–100%), sensibilidade e cor do peaking. |

False color tem prioridade sobre a zebra; o peaking fica por cima de tudo. As opções podem ser
mudadas durante a gravação e ficam salvas.

**Status:** espaço livre (minutos), estado térmico (só aparece de "leve" para cima) e o aquecimento
em % (`getThermalHeadroom`, a partir de 70%). Ao lado do botão de gravar, o VU do áudio (−60 a 0 dBFS,
marca em −12, aviso de CLIP). Fora da gravação, o VU usa um medidor separado, que para antes do take.

## Como funciona

- Shaders em GLSL ES 3.00 (`GL_OES_EGL_image_external_essl3` para a textura da câmera e `sampler3D`
  para a LUT, em `RGB16F` com filtro linear). Validados com o `glslc` do NDK.
- Scopes: a cada 4 quadros, o sinal é desenhado num framebuffer de 128×72 e lido com `glReadPixels`
  (36 KB); o histograma e o waveform são calculados na CPU. Não há um terceiro stream na sessão da
  câmera (um `ImageReader` a mais arriscaria a combinação de streams em 10 bits).

## Testar no S23

- [ ] LUT 709 ligada: imagem "normal"; comparar com o mesmo clipe + LUT no DaVinci.
- [ ] False color: um cartão cinza (ou parede neutra) bem exposto fica verde; janela estourada fica vermelha.
- [ ] Zebra a 90% numa janela/céu; mudar para 80% no ⚙ e ver a área crescer.
- [ ] Peaking: bordas pintadas mudam ao puxar o foco.
- [ ] Scopes: tampar a lente → tudo embaixo; apontar para uma lâmpada → pico em cima. O waveform
      deve ter a esquerda/direita iguais às da imagem (senão está espelhado).
- [ ] VU mexe falando, antes e durante a gravação; CLIP aparece com um grito perto do microfone.
- [ ] Com tudo ligado em 4K30: `quadrosPerdidos` no JSON não deve subir em relação a tudo desligado.
