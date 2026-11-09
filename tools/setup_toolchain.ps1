# Portable Android build toolchain (no admin, no Android Studio).
# JDK 17 (Microsoft build) + Android cmdline-tools + SDK packages + Gradle.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$root = "$env:USERPROFILE\android-toolchain"
New-Item -ItemType Directory -Force $root | Out-Null
Set-Location $root

function Get-Zip($url, $zip, $dest) {
  if (Test-Path $dest) { "skip $dest"; return }
  "download $url"
  Invoke-WebRequest $url -OutFile $zip
  Expand-Archive $zip -DestinationPath $dest
  Remove-Item $zip
}

Get-Zip 'https://aka.ms/download-jdk/microsoft-jdk-17-windows-x64.zip' "$root\jdk.zip" "$root\jdk"
$jdkHome = (Get-ChildItem "$root\jdk" -Directory | Select-Object -First 1).FullName
$env:JAVA_HOME = $jdkHome

Get-Zip 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip' "$root\clt.zip" "$root\clt-tmp"
$sdk = "$root\sdk"
if (-not (Test-Path "$sdk\cmdline-tools\latest")) {
  New-Item -ItemType Directory -Force "$sdk\cmdline-tools" | Out-Null
  Move-Item "$root\clt-tmp\cmdline-tools" "$sdk\cmdline-tools\latest"
}

Get-Zip 'https://services.gradle.org/distributions/gradle-8.10.2-bin.zip' "$root\gradle.zip" "$root\gradle"

$sdkm = "$sdk\cmdline-tools\latest\bin\sdkmanager.bat"
# PowerShell piping "y" does not reach sdkmanager reliably; use cmd's echo loop.
cmd /c "(for /l %i in (1,1,30) do @echo y) | `"$sdkm`" --sdk_root=`"$sdk`" --licenses" | Out-Null
& $sdkm --sdk_root=$sdk "platform-tools" "platforms;android-35" "build-tools;35.0.0"

# Persist for future shells (user scope, no admin)
[Environment]::SetEnvironmentVariable('JAVA_HOME', $jdkHome, 'User')
[Environment]::SetEnvironmentVariable('ANDROID_HOME', $sdk, 'User')
"JAVA_HOME=$jdkHome"
"ANDROID_HOME=$sdk"
& "$jdkHome\bin\java.exe" -version
& "$sdk\platform-tools\adb.exe" version
"TOOLCHAIN_OK"
