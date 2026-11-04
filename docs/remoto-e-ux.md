# Controle remoto pela web, rotação e tela limpa

## Controle remoto

Para deixar o celular posicionado (tripé, suporte) e operar de longe, pelo PC ou por outro celular.

1. No app: botão **REMOTO** (última da barra de baixo) → **Ligar remoto**. Aparecem o **PIN** e os
   endereços, por exemplo `http://192.168.18.172:8080 (Wi-Fi)`.
2. No outro aparelho, na **mesma rede**, abra esse endereço no navegador e digite o PIN. O navegador
   lembra o PIN; também dá para abrir direto com `http://IP:8080/?k=PIN`.
3. **Sem roteador:** toque em **Criar rede do app**. O celular cria uma rede Wi-Fi própria (nome e
   senha aparecem no painel; sem internet). Conecte o PC/celular a ela e abra o endereço marcado
   "rede do app". Na primeira vez, o Android pede a permissão de "dispositivos Wi-Fi próximos".

A página tem: preview ao vivo (~10 fps, com LUT/false color/zebra/peaking aplicados, como na tela),
**clique na imagem para focar**, gravar/parar (também na **barra de espaço**), cronômetro, VU, lente,
modo, Log, monitor, LUT, histograma, exposição (auto/travar/manual, ISO, obturador, 180°), WB
(auto/travar/Kelvin), foco (AF/travar, distância, marcar e puxar A/B), presets e ganho do áudio.

O remoto volta ligado se o app for reaberto. **Novo PIN** troca o PIN (quem estava conectado precisa
digitar de novo). Rotas: `GET /`, `GET /api/state?k=`, `POST /api/cmd?k=` (`{"acao": ...}`),
`GET /preview.mjpeg?k=`, `GET /preview.jpg?k=`.

Notas: o preview remoto só é gerado enquanto alguém está assistindo. Ele custa um pouco de GPU e
aquece; feche a aba quando não precisar. O app precisa ficar aberto e com a tela ligada (ela não
apaga sozinha).

## Tela bloqueada

Com o **remoto ligado** ou **durante uma gravação**, dá para **bloquear a tela** (botão lateral): a
câmera, a gravação, o áudio e o controle remoto (inclusive o preview) continuam. Enquanto isso fica a
notificação fixa "LogCam" (serviço em primeiro plano de câmera + microfone, exigência do Android para
usar câmera/microfone com a tela apagada). Com a tela apagada o app não desenha o preview na tela, o
que poupa GPU, tela e calor. Ao desbloquear, a tela volta onde estava.

Conferido no S23: tela em "Dozing", preview remoto a ~10 fps e um take de 6 s gravado pelo PC
(29,8 fps, áudio completo).

Limites: o serviço só pode ser ligado com o app aberto e visível (regra do Android), então ligue o
remoto ou comece a gravar antes de bloquear. Sem remoto e sem gravação, bloquear a tela fecha a câmera
como antes (economia total).

## Rotação

O app gira com o aparelho (`fullSensor`). Em **retrato**: chips em cima, barra de controles rolando
na horizontal, gravar/cronômetro/VU embaixo, e o vídeo sai em retrato (a rotação vai no MP4). A
orientação é **travada durante a gravação**, para o take não mudar no meio; destrava ao parar.

## Tela limpa

- As mensagens somem sozinhas (5 a 15 s, conforme a importância); o resumo do take some em 8 s.
- O aquecimento virou um chip pequeno (`🌡 97%`), branco/amarelo/vermelho; o aviso em texto aparece
  uma vez ao esquentar.
- Botão **⊘** (ao lado do gravar) esconde todos os widgets: ficam só o gravar, o cronômetro e o **👁**
  para mostrar de novo. Avisos que exigem ação (calor, espaço) continuam aparecendo.

## Testar

- [ ] Girar para retrato e para os dois lados de paisagem: layout certo e imagem em pé.
- [ ] Gravar em retrato: o arquivo abre em pé no PC. Durante o take, girar não muda a tela.
- [ ] ⊘ / 👁 escondem e mostram os widgets.
- [ ] Remoto pela mesma rede: abrir no PC, gravar, parar, mudar ISO, clicar para focar.
- [ ] Remoto ligado, bloquear a tela, gravar e parar pelo PC; o arquivo sai inteiro e com áudio.
- [ ] Remoto pela rede do app: criar, conectar o outro celular, abrir o endereço "rede do app".
