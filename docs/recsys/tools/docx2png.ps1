# 把 .docx 渲染成逐页 PNG（内部用本机 Word 导出 PDF，再用 poppler 转图）
# 用法: pwsh -File docx2png.ps1 -Docx <路径> [-OutDir <目录>] [-Dpi 110]
param(
    [Parameter(Mandatory=$true)][string]$Docx,
    [string]$OutDir,
    [int]$Dpi = 110
)
$ErrorActionPreference = 'Stop'
$Docx = (Resolve-Path -LiteralPath $Docx).Path
if (-not $OutDir) { $OutDir = Join-Path (Split-Path -Parent $Docx) 'render' }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
Get-ChildItem -LiteralPath $OutDir -Filter 'page-*.png' -ErrorAction SilentlyContinue | Remove-Item -Force

$pdf = Join-Path $OutDir ([IO.Path]::GetFileNameWithoutExtension($Docx) + '.pdf')
$word = New-Object -ComObject Word.Application
$word.Visible = $false
$word.DisplayAlerts = 0
try {
    $doc = $word.Documents.Open($Docx, $false, $false)
    for ($i = 1; $i -le $doc.Fields.Count; $i++) { $doc.Fields.Item($i).Update() | Out-Null }
    if ($doc.TablesOfContents.Count -gt 0) { $doc.TablesOfContents.Item(1).Update() }
    $doc.Save()
    $doc.ExportAsFixedFormat($pdf, 17)
    $doc.Close(0)
} finally {
    $word.Quit()
}
[System.Runtime.InteropServices.Marshal]::ReleaseComObject($word) | Out-Null

$poppler = 'C:\Users\26373\.cache\codex-runtimes\codex-primary-runtime\dependencies\native\poppler\Library\bin\pdftoppm.exe'
& $poppler -png -r $Dpi $pdf (Join-Path $OutDir 'page')
Write-Host "PDF: $pdf"
Get-ChildItem -LiteralPath $OutDir -Filter 'page-*.png' | Sort-Object Name | Select-Object Name, Length | Format-Table -AutoSize | Out-String -Width 120

