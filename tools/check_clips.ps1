# Confere os clipes de teste da Fase 0 com o ffprobe.
# Uso: .\tools\check_clips.ps1 <pasta com os .mp4>
# Pasta típica: adb pull /sdcard/DCIM/LogCam/probe .\clips
param([Parameter(Mandatory = $true)][string]$Folder)

$rows = foreach ($f in Get-ChildItem -Path $Folder -Filter *.mp4 | Sort-Object Name) {
    $json = & ffprobe -v error -select_streams v:0 -count_packets `
        -show_entries stream=codec_name,profile,pix_fmt,width,height,r_frame_rate,avg_frame_rate,color_transfer,color_primaries,bit_rate,nb_read_packets `
        -of json $f.FullName | ConvertFrom-Json
    $s = $json.streams[0]
    [pscustomobject]@{
        Clipe     = $f.BaseName
        Codec     = "$($s.codec_name) $($s.profile)"
        PixFmt    = $s.pix_fmt
        Tamanho   = "$($s.width)x$($s.height)"
        Fps       = $s.avg_frame_rate
        Quadros   = $s.nb_read_packets
        Mbps      = if ($s.bit_rate) { [math]::Round([double]$s.bit_rate / 1e6, 1) } else { "" }
        Transfer  = $s.color_transfer
        Primaries = $s.color_primaries
        "10 bits" = if ($s.pix_fmt -like "*10*") { "SIM" } else { "não" }
    }
}
$rows | Format-Table -AutoSize
