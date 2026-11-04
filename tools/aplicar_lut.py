#!/usr/bin/env python3
"""Converte um take do LogCam (Log, BT.2020, 10 bits) para Rec.709 com a LUT, do jeito certo, de ponta a ponta.

Por que existe: a LUT já faz TUDO (inversa da curva Log + BT.2020 -> BT.709 + gama 709). Ela precisa receber
o sinal como o app gravou. Qualquer conversão de gamut ou de transferência ANTES dela (como o "converter
wide-gamut na extração" de ferramentas de edição) duplica a conversão: a saturação sobe ~20% e a imagem
esquenta. Medido num take real: saturação 0,358 (certo) contra 0,438 (conversão antes da LUT).

O que o filtro faz:
  1. YCbCr -> RGB lendo o arquivo como BT.2020 NCL, faixa limitada -> RGB cheio de 16 bits (sem mexer em gamut);
  2. lut3d (interpolação tetraédrica) com a LUT;
  3. RGB -> YCbCr BT.709, faixa limitada, e o arquivo sai marcado BT.709 (SDR de verdade, sem ambiguidade).

Uso:
  python tools/aplicar_lut.py take.mp4                      # take_709.mp4 (HEVC 10 bits, CRF 14)
  python tools/aplicar_lut.py take.mp4 --lut filme          # com o look criativo
  python tools/aplicar_lut.py take.mp4 --lut sem_gamut      # variante sem conversão de gamut (A/B)
  python tools/aplicar_lut.py take.mp4 --codec prores       # ProRes 422 HQ, para editar sem recompressão
  python tools/aplicar_lut.py take.mp4 --seconds 5          # só os 5 primeiros segundos (teste)
Requer ffmpeg no PATH.
"""
import argparse
import subprocess
import sys
from pathlib import Path

LUTS = Path(__file__).resolve().parent.parent / "luts"
NAMES = {
    "tecnica": "LogCam_to_Rec709.cube",
    "sem_gamut": "LogCam_to_Rec709_sem_gamut.cube",
    "filme": "LogCam_Look_Filme.cube",
}


def video_filter(lut_name: str, pix_fmt: str) -> str:
    # O lut3d recebe o caminho relativo ao diretório de trabalho do ffmpeg (cwd = luts/): evita o
    # escape de "C:\\" que o parser de filtros do ffmpeg exige no Windows.
    return (
        "scale=in_color_matrix=bt2020:in_range=tv:out_range=pc:flags=accurate_rnd+full_chroma_int,"
        "format=gbrp16le,"
        f"lut3d=file={lut_name}:interp=tetrahedral,"
        "scale=out_color_matrix=bt709:out_range=tv:flags=accurate_rnd+full_chroma_int,"
        f"format={pix_fmt},"
        # O ffmpeg herda as tags do quadro de entrada (bt2020/unknown) mesmo com -color_*: setparams as troca.
        "setparams=colorspace=bt709:color_primaries=bt709:color_trc=bt709:range=tv"
    )


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("clip", type=Path)
    ap.add_argument("--out", type=Path)
    ap.add_argument("--lut", choices=sorted(NAMES), default="tecnica")
    ap.add_argument("--codec", choices=["hevc", "prores", "h264"], default="hevc")
    ap.add_argument("--crf", type=int, default=14)
    ap.add_argument("--seconds", type=float, help="converter só os N primeiros segundos")
    args = ap.parse_args()

    lut = LUTS / NAMES[args.lut]
    if not lut.exists():
        sys.exit(f"LUT não encontrada: {lut} (rode python tools/make_lut.py)")
    out = args.out or args.clip.with_name(f"{args.clip.stem}_709.{'mov' if args.codec == 'prores' else 'mp4'}")

    if args.codec == "prores":
        pix, enc = "yuv422p10le", ["-c:v", "prores_ks", "-profile:v", "3"]
    elif args.codec == "h264":
        pix, enc = "yuv420p", ["-c:v", "libx264", "-preset", "slow", "-crf", str(args.crf)]
    else:
        pix, enc = "yuv420p10le", ["-c:v", "libx265", "-preset", "medium", "-crf", str(args.crf),
                                   "-x265-params", "log-level=error"]
    cmd = ["ffmpeg", "-y", "-hide_banner", "-v", "error", "-i", str(args.clip.resolve())]
    if args.seconds:
        cmd += ["-t", str(args.seconds)]
    cmd += ["-vf", video_filter(lut.name, pix), *enc,
            "-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709", "-color_range", "tv",
            "-c:a", "copy", str(out.resolve())]
    print(f"{args.clip.name} -> {out.name}  (LUT: {lut.name})")
    subprocess.run(cmd, check=True, cwd=LUTS)
    print("pronto:", out)


if __name__ == "__main__":
    main()
