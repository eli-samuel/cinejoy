# Creates the fixed release signing key used by every build (see README "Signing key").
#
# Run once, from PowerShell:
#   powershell -ExecutionPolicy Bypass -File scripts\create-signing-key.ps1
#
# The key file is saved outside the repo. Back it up together with the password: without them,
# future builds can't update the apps already installed on the TV.

param(
    [string]$Out = (Join-Path $HOME "fire-tv-apps-release.jks")
)

$ErrorActionPreference = "Stop"

if (Test-Path $Out) {
    Write-Error "$Out already exists. Delete or move it first if you really want a new key."
}

$keytool = (Get-Command keytool -ErrorAction SilentlyContinue).Source
if (-not $keytool) {
    $keytool = Get-ChildItem "C:\Program Files\Java\*\bin\keytool.exe", "C:\Program Files\Eclipse Adoptium\*\bin\keytool.exe", "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
}
if (-not $keytool) {
    Write-Error "keytool not found. Install a JDK (e.g. Eclipse Temurin 17) and try again."
}

$secure = Read-Host "Choose a password for the key (at least 6 characters)" -AsSecureString
$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try {
    $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
}
if ($password.Length -lt 6) {
    Write-Error "The password must be at least 6 characters."
}

& $keytool -genkeypair -keystore $Out -storetype PKCS12 -alias release -keyalg RSA -keysize 2048 `
    -validity 10000 -storepass $password -keypass $password -dname "CN=Fire TV Apps"
if ($LASTEXITCODE -ne 0) {
    Write-Error "keytool failed."
}

[Convert]::ToBase64String([IO.File]::ReadAllBytes($Out)) | Set-Clipboard

Write-Host ""
Write-Host "Key saved to $Out. Back it up (and the password) somewhere safe."
Write-Host ""
Write-Host "Now add two secrets at GitHub > repo > Settings > Secrets and variables > Actions > New repository secret:"
Write-Host "  KEYSTORE_BASE64   = paste from the clipboard (already copied)"
Write-Host "  KEYSTORE_PASSWORD = the password you just chose"
