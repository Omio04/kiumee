param(
    [string]$Source = (Join-Path $PSScriptRoot '../.local/mega/10-loyalty.png'),
    [string]$Destination = (Join-Path $PSScriptRoot '../.local/mega/10-loyalty.luma')
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
Add-Type -ReferencedAssemblies System.Drawing -TypeDefinition @'
using System;
using System.IO;
using System.Drawing;
public static class ExampleLumaReader {
    public static void Extract(string source, string destination) {
        using (var image = new Bitmap(source))
        using (var stream = File.Create(destination))
        using (var writer = new BinaryWriter(stream)) {
            writer.Write(image.Width);
            writer.Write(image.Height);
            for (var y = 0; y < image.Height; y++)
                for (var x = 0; x < image.Width; x++) {
                    var pixel = image.GetPixel(x, y);
                    writer.Write((byte)((pixel.R * 77 + pixel.G * 150 + pixel.B * 29) >> 8));
                }
        }
    }
}
'@
[ExampleLumaReader]::Extract([IO.Path]::GetFullPath($Source), [IO.Path]::GetFullPath($Destination))
Write-Output "Extracted luminance bytes: $Destination"
