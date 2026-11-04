# Fase 6: LUT de conversão e fluxo de edição

Uma LUT técnica, gerada da **mesma curva** da Fase 2 (`color/LogCurve.kt`), converte o Log para
Rec.709. O mesmo arquivo `.cube` serve no preview do app (chip **LUT**, empacotado nos assets) e no
editor.

## As LUTs (`luts/`)

| Arquivo | Para quê |
|---|---|
| `LogCam_to_Rec709.cube` | **Técnica**: conversão fiel (inversa da Log → BT.2020→BT.709 em luz linear → rolloff suave acima de 0,8 → gama 709). Corrija em cima dela. Cinza 18% gravado em 0,41 sai em 0,409. |
| `LogCam_Look_Filme.cube` | **Criativa** (opcional): a técnica + contraste em S suave, sombras levemente frias, altas levemente quentes, +8% de saturação. Ponto de partida de estilo. |

Geradas por `python tools/make_lut.py` (33³). Se a curva mudar, rode de novo e commite as duas; o app
pega as `.cube` novas no próximo build. Para uma LUT mais fina (65³) só para o editor:
`python tools/make_lut.py --size 65 --out luts/LogCam_to_Rec709_65.cube --look none`.

No app, o ⚙ escolhe qual LUT o chip **LUT** aplica ao preview.

## Validação com cartão cinza

1. Cartão cinza 18% (ou um cinza neutro de papelaria) ocupando o centro da imagem, luz uniforme.
2. No app: false color ligado, ajuste a exposição até o cartão ficar **verde**; WB em Kelvin da luz
   (ou TRAVAR em cima do cartão). Grave 3 s.
3. No PC:
   ```
   python tools/validar_cinza.py LogCam_xxxx.mp4
   ```
   Mede a média do quadrado central (10% da imagem; mude com `--roi x,y,w,h`) e informa o valor Log
   (alvo 0,41), a exposição em stops, o valor depois da LUT (alvo 0,409, o meio do waveform) e a
   neutralidade entre R, G e B. Sai com código 0 se a exposição está a ±1/3 stop e o WB está neutro.
4. Se houver uma carta de cores, grave também e confira no DaVinci (vectorscope) com a LUT técnica:
   as cores devem cair nas caixas sem uma rotação geral de matiz.

O script foi testado com clipes sintéticos (cinza 0,41 → −0,04 stop, neutro; um cinza puxado para o
vermelho → reprovado no WB).

## Edição no PC: DaVinci Resolve

1. **Project Settings → Color Management**: Color science = **DaVinci YRGB** (sem "Color Managed",
   sem ACES), Timeline = Rec.709 Gamma 2.4.
2. Copie as `.cube` para a pasta de LUTs (botão **Open LUT Folder**) e clique em **Update Lists**.
3. Na página **Color**, monte os nós assim:
   - **Nó 1, correção primária em Log**: exposição (Offset), balanço (Offset/Gain por canal). Mexer
     antes da LUT preserva os realces.
   - **Nó 2, LUT técnica** (`LogCam_to_Rec709`): botão direito → LUT.
   - **Nó 3 em diante, look**: contraste, saturação, ou a `LogCam_Look_Filme` no lugar da técnica.
4. Se o clipe abrir escuro ou "HDR", veja em **Clip Attributes** se ele não está interpretado como
   HLG (o app grava a transferência como "não especificada" e os metadados `com.logcam.*` justamente para isso
   não acontecer).
5. Estabilização: o `.gcsv` do mesmo take vai para o **Gyroflow** (ver `docs/fase5.md`). Exporte do
   Gyroflow em ProRes/DNxHR e só depois faça a cor, ou use o plugin OpenFX do Gyroflow no Resolve.

## Edição no celular: CapCut

1. Copie as `.cube` para o celular. No CapCut: clipe → **Ajustar → LUT → Importar**.
2. Use a técnica a 100% e faça os ajustes **depois** com cuidado: o CapCut trabalha em 8 bits, então
   ajustes fortes podem gerar banding. Exponha certo na gravação (false color) para mexer pouco.
3. O CapCut ignora a tag BT.2020, mas como a própria LUT converte o gamut, isso não importa.

## Testar

- [ ] `python tools/make_lut.py` regenera as duas LUTs sem mudar a técnica (git diff vazio).
- [ ] Cartão cinza: `validar_cinza.py` dá exposição OK com o cartão verde no false color.
- [ ] O mesmo clipe no DaVinci (nó com a LUT técnica) fica igual ao preview do app com LUT ligada.
- [ ] A LUT criativa aparece no ⚙ do app e no CapCut.
