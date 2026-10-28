param([Parameter(Mandatory=$true)][string]$Directory, [switch]$RemoveTrust, [switch]$SkipUserTrust)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath($Directory)
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
if ((Split-Path $root -Parent).TrimEnd('\') -ne $tempRoot.TrimEnd('\') -or (Split-Path $root -Leaf) -notlike 'ls-core-migrations-test-*') { throw 'Expected owned disposable directory' }
$thumbFile = Join-Path $root 'pilot-ca-thumbprint.txt'
if ($RemoveTrust) {
    if (Test-Path -LiteralPath $thumbFile) {
        $thumb = (Get-Content -LiteralPath $thumbFile -Raw).Trim()
        if ($thumb -notmatch '^[A-F0-9]{40}$') { throw 'Invalid owned certificate thumbprint' }
        $owned = Get-Item -LiteralPath "Cert:\CurrentUser\Root\$thumb" -ErrorAction SilentlyContinue
        if ($owned) {
            if ($owned.Subject -ne 'CN=LS Core disposable pilot CA') { throw 'Unexpected certificate subject' }
            Remove-Item -LiteralPath "Cert:\CurrentUser\Root\$thumb"
        }
    }
    foreach ($file in @('pilot-ca-key-thumbprint.txt','pilot-leaf-key-thumbprint.txt')) {
        $keyFile = Join-Path $root $file
        if (Test-Path -LiteralPath $keyFile) {
            $keyThumb = (Get-Content -LiteralPath $keyFile -Raw).Trim()
            if ($keyThumb -notmatch '^[A-F0-9]{40}$') { throw 'Invalid owned key thumbprint' }
            $keyCert = Get-Item -LiteralPath "Cert:\CurrentUser\My\$keyThumb" -ErrorAction SilentlyContinue
            if ($keyCert) {
                $expectedSubject = if ($file -eq 'pilot-ca-key-thumbprint.txt') { 'CN=LS Core disposable pilot CA' } else { 'CN=localhost' }
                if ($keyCert.Subject -ne $expectedSubject) { throw 'Unexpected owned key subject' }
                Remove-Item -LiteralPath "Cert:\CurrentUser\My\$keyThumb" -DeleteKey
            }
        }
    }
    exit 0
}
if (!$env:LS_PILOT_TLS_PASSWORD -or (Test-Path -LiteralPath $thumbFile)) { throw 'Expected fresh TLS setup and process password' }
$ca = $null
$leaf = $null
try {
    $ca = New-SelfSignedCertificate -Type Custom -Subject 'CN=LS Core disposable pilot CA' -KeyAlgorithm RSA -KeyLength 2048 -HashAlgorithm SHA256 -KeyExportPolicy NonExportable -KeyUsage CertSign,CRLSign,DigitalSignature -NotAfter (Get-Date).AddDays(2) -CertStoreLocation Cert:\CurrentUser\My -TextExtension @('2.5.29.19={critical}{text}ca=TRUE&pathlength=0')
    $ca.Thumbprint | Set-Content -LiteralPath (Join-Path $root 'pilot-ca-key-thumbprint.txt') -Encoding ASCII
    $leaf = New-SelfSignedCertificate -Type Custom -Subject 'CN=localhost' -Signer $ca -KeyAlgorithm RSA -KeyLength 2048 -HashAlgorithm SHA256 -KeyExportPolicy Exportable -KeyUsage DigitalSignature,KeyEncipherment -NotAfter (Get-Date).AddDays(1) -CertStoreLocation Cert:\CurrentUser\My -TextExtension @('2.5.29.17={text}DNS=localhost&IPAddress=127.0.0.1','2.5.29.19={critical}{text}ca=FALSE','2.5.29.37={text}1.3.6.1.5.5.7.3.1')
    $leaf.Thumbprint | Set-Content -LiteralPath (Join-Path $root 'pilot-leaf-key-thumbprint.txt') -Encoding ASCII
    $caPath = Join-Path $root 'pilot-ca.cer'
    Export-Certificate -Cert $ca -FilePath $caPath | Out-Null
    [IO.File]::WriteAllText((Join-Path $root 'pilot-ca.pem'), "-----BEGIN CERTIFICATE-----`n" + [Convert]::ToBase64String($ca.RawData, [Base64FormattingOptions]::InsertLineBreaks) + "`n-----END CERTIFICATE-----`n", [Text.Encoding]::ASCII)
    $ca.Thumbprint | Set-Content -LiteralPath $thumbFile -Encoding ASCII
    if (!$SkipUserTrust) { Import-Certificate -FilePath $caPath -CertStoreLocation Cert:\CurrentUser\Root | Out-Null }
    $secret = ConvertTo-SecureString -String $env:LS_PILOT_TLS_PASSWORD -AsPlainText -Force
    Export-PfxCertificate -Cert $leaf -FilePath (Join-Path $root 'pilot.p12') -Password $secret -ChainOption EndEntityCertOnly | Out-Null
} finally {
    # Exported leaf key is encrypted; no signing key is retained after setup.
    if ($leaf) { Remove-Item -LiteralPath "Cert:\CurrentUser\My\$($leaf.Thumbprint)" -DeleteKey }
    if ($ca) { Remove-Item -LiteralPath "Cert:\CurrentUser\My\$($ca.Thumbprint)" -DeleteKey }
}
