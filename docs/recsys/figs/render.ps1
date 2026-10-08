# 批量把 figs 目录下的 .mmd 渲染为 PNG + SVG
# 用法：pwsh -File render.ps1                  渲染全部
#       pwsh -File render.ps1 -Filter fig01*    只渲染匹配的图
param([string]$Filter = '*.mmd')

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$pptr = Join-Path $here 'puppeteer-config.json'
$mcfg = Join-Path $here 'mermaid-config.json'

$files = Get-ChildItem -LiteralPath $here -Filter $Filter -File | Where-Object { $_.Extension -eq '.mmd' }
if (-not $files) { Write-Host "没有匹配 $Filter 的 .mmd 文件"; exit 0 }

foreach ($f in $files) {
    $base = $f.BaseName
    & mmdc -p $pptr -c $mcfg -i $f.FullName -o (Join-Path $here "$base.png") -w 1800 -b white -s 2 | Out-Null
    & mmdc -p $pptr -c $mcfg -i $f.FullName -o (Join-Path $here "$base.svg") -b white | Out-Null
    Write-Host "[完成] $base.png / $base.svg"
}

