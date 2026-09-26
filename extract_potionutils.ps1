Add-Type -AssemblyName System.IO.Compression.FileSystem
$jarPath = "e:\Mod\ModTiaoshi\Tiaoshi-1.20.1\forge\build\moddev\artifacts\forge-1.20.1-47.2.30-sources.jar"
$jar = [System.IO.Compression.ZipFile]::OpenRead($jarPath)
$entry = $jar.Entries | Where-Object { $_.FullName -eq "net/minecraft/world/item/alchemy/PotionUtils.java" }
$reader = New-Object System.IO.StreamReader($entry.Open())
$lines = $reader.ReadToEnd() -split "`n"
for ($i = 1; $i -le $lines.Length; $i++) {
    if ($lines[$i-1] -match 'getMobEffects|getCustomEffects|setCustomEffects|getPotion|setPotion|class PotionUtils') {
        Write-Output ("{0}: {1}" -f $i, $lines[$i-1].Trim())
    }
}
$reader.Close()
$jar.Dispose()
