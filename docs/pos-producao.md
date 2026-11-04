# Pós-produção dos takes do LogCam

Referência para quem edita os arquivos (inclusive a skill de edição). Tudo aqui foi medido em takes reais
do S23; o que é suposição está marcado.

## O que o arquivo é

| Item | Valor |
|---|---|
| Codec | HEVC Main 10, `yuv420p10le`, 4K30 (ou o modo escolhido), faixa limitada (`tv`) |
| Cor declarada | `color_space=bt2020nc`, `color_primaries=bt2020`, `color_transfer=unknown` |
| Curva | Log próprio do app: `y = a·log10(x + c) + d`, `a=1,011514 c=0,170173 d=0,870964` (preto 0,093, cinza 18% = 0,41, branco 0,94), em `color/LogCurve.kt` |
| Gamut do conteúdo | **BT.2020** (é o do perfil HLG10 do Android). *Suposição não verificada com carta de cores.* |
| Áudio | AAC 48 kHz estéreo |

Não existe código padrão de transferência para essa curva, então a tag diz só "não especificada" (é a
convenção do Apple Log). O que declara que é Log são os metadados do MP4 (`ffprobe -show_entries format_tags`):

```
com.logcam.logprofile = LogCam Log 10-bit
com.logcam.curve      = y = a*log10(x + c) + d; a=... c=... d=...; preto=0.093 cinza18=0.41 branco=0.94
com.logcam.gamut      = BT.2020
com.logcam.lut        = LogCam_to_Rec709.cube
```

Takes gravados antes de 2026-11-04 (tarde) não têm essas chaves e podem ter `color_transfer=bt709`:
`python tools/marcar_log.py take.mp4` acrescenta as chaves sem recodificar. A tag antiga `bt709` não
atrapalha quem usa a LUT do jeito abaixo.

## O jeito certo de usar a LUT

A LUT faz **tudo**: inversa da curva + BT.2020→BT.709 + gama 709 + rolloff dos realces. Ela tem que receber
o sinal **como o app gravou**:

```
scale=in_color_matrix=bt2020:in_range=tv:out_range=pc:flags=accurate_rnd+full_chroma_int,format=gbrp16le,
lut3d=file=LogCam_to_Rec709.cube:interp=tetrahedral,
scale=out_color_matrix=bt709:out_range=tv:flags=accurate_rnd+full_chroma_int,format=yuv420p10le,
setparams=colorspace=bt709:color_primaries=bt709:color_trc=bt709:range=tv
```

`python tools/aplicar_lut.py take.mp4` roda exatamente isso (opções: `--lut tecnica|sem_gamut|filme`,
`--codec hevc|prores|h264`, `--seconds N` para testar). A saída sai marcada BT.709 de verdade.

### O que NÃO fazer antes da LUT

- **Nenhuma conversão de gamut ou de transferência** (`colorspace=...`, `zscale=p=bt709`, tonemap). Medido no
  quadro de 5 s de `LogCam_20261104-095220.mp4`:

| Caminho | Luma média | Saturação | |
|---|---|---|---|
| LUT direto no arquivo (certo) | 0,263 | 0,358 | pele natural, parede neutra |
| Conversão wide-gamut na extração e depois a LUT | 0,263 | **0,438** | +22% de saturação, imagem mais quente e rosada |

  A conversão da ferramenta trata o Log como se fosse BT.2020 com gama 709 e converte as primárias em cima de
  um sinal que não é linear nem é essa curva; depois a LUT converte de novo.
- Presets de outros fabricantes (Apple Log, ARRI, Sony, V-Log): são curvas diferentes. O preset de Apple Log
  esmaga as sombras desta curva.

### Plus Studio (aplicado em 2026-11-04)

O Plus Studio (`~/.claude/skills/plus-studio`) reconhece o LogCam e usa a LUT na extração:

- `helpers/logcam.py` (novo): reconhece pela tag `com.logcam.logprofile` (takes antigos: `LogCam_*` + 10 bits +
  BT.2020) e monta a cadeia acima. A LUT fica em `meus/luts/` (a pasta que as atualizações preservam).
- `render.py`: a LUT converte antes do grade e o `wide_gamut_chain` é pulado para esses takes.
- `grade.py`: o grade `auto` e a montagem `--candidates` partem da imagem já convertida.
- `detect_color.py`: perfil `logcam`, confiança alta (antes: ARRI LogC, só porque "logcam" contém "logc"; o
  nome do arquivo não tinha relação com isso).
- `LOGCAM_LUT=sem_gamut` troca para a variante sem conversão de gamut (A/B).

Conferido com as funções da própria skill no take `095220`: a saída do render tem saturação 0,357 (a certa; era
0,438 com a conversão duplicada), igual ao `aplicar_lut.py`; vídeos que não são do LogCam passam como antes.
As outras cópias (`edvid`, `edvid-antiga`, `edvid-lt`) não foram alteradas. Uma atualização do Plus Studio
substitui os helpers e desfaz isso (ver `meus/luts/LEIA-LogCam.md` na skill).

## A/B de gamut

Não há prova de que o ISP entrega BT.2020 de verdade com a curva customizada (só a documentação do perfil
HLG10 e a tag do encoder). Por isso existem duas LUTs técnicas:

| LUT | Pele (RGB) | Saturação média |
|---|---|---|
| `LogCam_to_Rec709` (com BT.2020→709) | 0,167 / 0,112 / 0,076 | 0,364 |
| `LogCam_to_Rec709_sem_gamut` | 0,148 / 0,116 / 0,081 | 0,300 (−18%) |

Compare no mesmo quadro (`aplicar_lut.py --lut sem_gamut`). Se a pele ou o céu saírem saturados demais com a
técnica, o dado provavelmente não está em BT.2020 e a `sem_gamut` é a certa. A prova definitiva é gravar uma
carta de cores e conferir no vectorscope.

## Medir "piso de preto"

`signalstats` com YMIN por quadro dá 36–49 (de 1023) neste arquivo, mas é o menor pixel de 8 milhões, ou
seja, ruído. O piso típico é o percentil 1: ~99 (≈0,04 na faixa limitada). Detectores que usam o mínimo por
quadro concluem "preto chega a zero" e não reconhecem o Log por estatística; use as tags.
