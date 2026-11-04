#!/usr/bin/env python3
"""Marca um MP4 do LogCam como Log, sem recodificar (acrescenta metadados ao final do arquivo).

O padrão de vídeo não tem um código para a curva Log do LogCam, então a tag de cor diz só "transferência
não especificada" (como o Apple Log faz). O que diz "isto é Log" são os metadados QuickTime que o app passa
a gravar sozinho em cada take (o mesmo formato do `com.apple.proapps.logprofile` da Apple):

    com.logcam.logprofile   LogCam Log 10-bit
    com.logcam.curve        a, c, d e as âncoras da curva (lidas de color/LogCurve.kt)
    com.logcam.gamut        BT.2020
    com.logcam.lut          LogCam_to_Rec709.cube

Este script faz o mesmo para takes gravados ANTES dessa mudança. Os dados de vídeo e áudio não são tocados:
só o final do arquivo (o box `moov`) cresce alguns centenas de bytes.

Uso:
  python tools/marcar_log.py clipe.mp4                 # grava clipe_log.mp4 ao lado (cópia)
  python tools/marcar_log.py clipe.mp4 --in-place      # altera o próprio arquivo
  python tools/marcar_log.py clipe.mp4 --out saida.mp4
"""
import argparse
import shutil
import struct
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import make_lut  # noqa: E402  (constantes da curva: fonte única)


def box(kind: bytes, payload: bytes) -> bytes:
    return struct.pack(">I4s", 8 + len(payload), kind) + payload


def metadata_box(tags: list[tuple[str, str]]) -> bytes:
    """`meta` QuickTime com chaves `mdta` (keys + ilst), como o Apple Log e a câmera do iPhone."""
    hdlr = box(b"hdlr", struct.pack(">I", 0) + b"\x00\x00\x00\x00" + b"mdta" + b"\x00" * 12 + b"\x00")
    keys = b"".join(box(b"mdta", k.encode()) for k, _ in tags)
    keys_box = box(b"keys", struct.pack(">II", 0, len(tags)) + keys)
    items = b""
    for i, (_, v) in enumerate(tags, start=1):
        data = box(b"data", struct.pack(">II", 1, 0) + v.encode())  # 1 = UTF-8, locale 0
        items += struct.pack(">I", 8 + len(data)) + struct.pack(">I", i) + data  # tipo do item = índice da chave
    ilst = box(b"ilst", items)
    return box(b"meta", hdlr + keys_box + ilst)


def log_tags() -> list[tuple[str, str]]:
    k = make_lut.read_constants(make_lut.LOGCURVE_KT)
    a, c, d = make_lut.solve_curve(k)
    return [
        ("com.logcam.logprofile", "LogCam Log 10-bit"),
        ("com.logcam.curve", f"y = a*log10(x + c) + d; a={a:.6f} c={c:.6f} d={d:.6f}; "
                             f"preto={k['BLACK_OUT']} cinza18={k['GRAY_OUT']} branco={k['WHITE_OUT']}"),
        ("com.logcam.gamut", "BT.2020"),
        ("com.logcam.lut", "LogCam_to_Rec709.cube"),
    ]


def top_level(f, size):
    pos = 0
    while pos < size:
        f.seek(pos)
        s, t = struct.unpack(">I4s", f.read(8))
        if s == 1:
            s = struct.unpack(">Q", f.read(8))[0]
        elif s == 0:
            s = size - pos
        yield pos, t, s
        pos += s


def children(buf: bytes, start: int, end: int):
    """(tipo, início, fim) dos boxes filhos em buf[start:end]."""
    pos = start
    while pos + 8 <= end:
        size, kind = struct.unpack(">I4s", buf[pos:pos + 8])
        if size < 8 or pos + size > end:
            break
        yield kind, pos, pos + size
        pos += size


def merged_meta(moov: bytes, tags: list[tuple[str, str]]) -> bytes:
    """Novo conteúdo do moov: o `meta` que o MediaMuxer já grava (com com.android.version) ganha as chaves
    do LogCam. O ffmpeg só lê UM bloco meta por arquivo; um segundo seria ignorado ou lido com as chaves
    erradas."""
    body_end = len(moov)
    for kind, s0, e0 in children(moov, 8, body_end):
        if kind != b"meta":
            continue
        # O meta do Android/QuickTime NÃO tem os 4 bytes de versão/flags (o hdlr vem logo após o cabeçalho);
        # o meta ISO tem. Distingue pelo tipo do primeiro filho.
        head = 8 if moov[s0 + 12:s0 + 16] == b"hdlr" else 12
        inner = list(children(moov, s0 + head, e0))
        keys = next((c for c in inner if c[0] == b"keys"), None)
        ilst = next((c for c in inner if c[0] == b"ilst"), None)
        if not keys or not ilst:
            break
        count = struct.unpack(">I", moov[keys[1] + 12:keys[1] + 16])[0]
        new_keys = box(b"keys", struct.pack(">II", 0, count + len(tags)) + moov[keys[1] + 16:keys[2]]
                       + b"".join(box(b"mdta", k.encode()) for k, _ in tags))
        items = b""
        for i, (_, v) in enumerate(tags, start=count + 1):
            data = box(b"data", struct.pack(">II", 1, 0) + v.encode())
            items += struct.pack(">II", 8 + len(data), i) + data
        new_ilst = box(b"ilst", moov[ilst[1] + 8:ilst[2]] + items)
        parts = []
        for kind2, s2, e2 in inner:
            parts.append(new_keys if (kind2, s2) == (b"keys", keys[1]) else new_ilst if (kind2, s2) == (b"ilst", ilst[1]) else moov[s2:e2])
        new_meta = struct.pack(">I4s", head + sum(map(len, parts)), b"meta") + moov[s0 + 8:s0 + head] + b"".join(parts)
        new_body = moov[8:s0] + new_meta + moov[e0:]
        return struct.pack(">I4s", 8 + len(new_body), b"moov") + new_body
    # Sem meta prévio: acrescenta o nosso.
    return struct.pack(">I4s", 8 + len(moov) - 8 + len(metadata_box(tags)), b"moov") + moov[8:] + metadata_box(tags)


def tag_file(path: Path) -> str:
    with open(path, "r+b") as f:
        f.seek(0, 2)
        size = f.tell()
        moov = [b for b in top_level(f, size) if b[1] == b"moov"]
        if not moov:
            return "sem box moov: arquivo incompleto"
        pos, _, msize = moov[0]
        if pos + msize != size:
            return "o moov não é o último box (arquivo 'faststart'): não dá para marcar sem reescrever o arquivo"
        f.seek(pos)
        old = f.read(msize)
        if struct.unpack(">I", old[:4])[0] == 1 or b"com.logcam.logprofile" in old:
            return "já marcado" if b"com.logcam.logprofile" in old else "moov com tamanho de 64 bits: não suportado"
        new = merged_meta(old, log_tags())
        # O moov é o último box e o mdat vem antes: reescrever só o moov não muda nenhum offset de amostra.
        f.seek(pos)
        f.write(new)
        f.truncate()
    return "ok"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("clip", type=Path)
    ap.add_argument("--out", type=Path)
    ap.add_argument("--in-place", action="store_true")
    args = ap.parse_args()
    target = args.clip
    if not args.in_place:
        target = args.out or args.clip.with_name(args.clip.stem + "_log" + args.clip.suffix)
        shutil.copyfile(args.clip, target)
    r = tag_file(target)
    print(f"{target}: {r}")
    if r not in ("ok", "já marcado"):
        if not args.in_place:
            target.unlink(missing_ok=True)
        sys.exit(1)


if __name__ == "__main__":
    main()
