param(
    [ValidateSet("Menu", "InstallLatestApk", "GrantPermissions", "BuildApkAndAab", "OpenManual", "OpenAssets", "OpenDist")]
    [string]$Action = "Menu",
    [switch]$Elevate
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

# 필요 시 관리자 권한 자동 상승 (UAC Self-Elevation)
if ($Elevate) {
    $currentPrincipal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
    if (-not $currentPrincipal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        $argList = "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`" -Action $Action"
        Start-Process powershell.exe -Verb RunAs -ArgumentList $argList
        exit
    }
}

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$DistDir = Split-Path -Parent $ScriptDir
$ProjectRoot = Split-Path -Parent $DistDir
$PackageName = "com.autologue.app"

function Resolve-AdbPath {
    $cmd = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $candidates = @(
        (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"),
        "C:\Android\Sdk\platform-tools\adb.exe"
    )
    foreach ($c in $candidates) {
        if (Test-Path $c) { return $c }
    }
    return $null
}

function Get-LatestInstallApk {
    $apks = Get-ChildItem -Path $DistDir -Filter "나의일상다이어리_v*.apk" -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike "*_release.apk" } |
        Sort-Object {
            if ($_.BaseName -match 'v(\d+)\.(\d+)\.(\d+)') {
                [version]"$($Matches[1]).$($Matches[2]).$($Matches[3])"
            } else {
                [version]"0.0.0"
            }
        } -Descending
    if ($apks -and $apks.Count -gt 0) { return $apks[0] }

    $anyApk = Get-ChildItem -Path $DistDir -Filter "*.apk" -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    return $anyApk
}

function Get-LatestAab {
    return Get-ChildItem -Path $DistDir -Filter "나의일상다이어리_v*.aab" -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
}

function Invoke-InstallLatestApk {
    $adb = Resolve-AdbPath
    if (-not $adb) {
        Write-Host "[오류] Android SDK platform-tools(adb.exe)를 찾을 수 없습니다." -ForegroundColor Red
        return
    }
    $apk = Get-LatestInstallApk
    if (-not $apk) {
        Write-Host "[오류] dist 폴더에 설치 가능한 APK 파일이 없습니다." -ForegroundColor Red
        return
    }
    Write-Host "[안내] 연결된 Android 기기 확인 중..."
    & $adb devices
    Write-Host "[설치] $($apk.Name) ($([math]::Round($apk.Length / 1MB, 2)) MB) 설치를 시작합니다..."
    & $adb install -r $apk.FullName
    if ($LASTEXITCODE -eq 0) {
        Write-Host "[완료] 앱 설치에 성공했습니다. 메인 화면을 실행합니다." -ForegroundColor Green
        & $adb shell am start -n "$PackageName/.MainActivity" | Out-Null
    } else {
        Write-Host "[오류] APK 설치 중 오류가 발생했습니다. USB 디버깅 허용 여부를 확인해 주세요." -ForegroundColor Red
    }
}

function Invoke-GrantPermissions {
    $adb = Resolve-AdbPath
    if (-not $adb) {
        Write-Host "[오류] adb.exe를 찾을 수 없습니다." -ForegroundColor Red
        return
    }
    $permissions = @(
        "android.permission.RECEIVE_SMS",
        "android.permission.READ_SMS",
        "android.permission.RECEIVE_MMS",
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.ACCESS_MEDIA_LOCATION",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.ACCESS_BACKGROUND_LOCATION",
        "android.permission.BLUETOOTH_CONNECT",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.CAMERA"
    )
    Write-Host "[권한] 필수 런타임 권한을 일괄 허용합니다..."
    foreach ($perm in $permissions) {
        & $adb shell pm grant $PackageName $perm 2>$null
        Write-Host "  - 허용 처리: $perm"
    }
    Write-Host "[안내] 배터리 사용량 제한 해제(백그라운드 주행·동기화 보호)를 적용합니다..."
    & $adb shell dumpsys deviceidle whitelist +$PackageName | Out-Null
    Write-Host "[안내] 스마트폰 화면에 알림 접근 권한 설정 창을 엽니다. '나의일상다이어리'를 켜 주세요."
    & $adb shell am start -a android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS | Out-Null
    Write-Host "[완료] 권한 일괄 설정이 마무리되었습니다." -ForegroundColor Green
}

function Invoke-BuildApkAndAab {
    $gradlew = Join-Path $ProjectRoot "gradlew.bat"
    if (-not (Test-Path $gradlew)) {
        Write-Host "[오류] 프로젝트 루트에서 gradlew.bat을 찾을 수 없습니다: $gradlew" -ForegroundColor Red
        return
    }
    Write-Host "[빌드] 신규 APK(Debug/Release) 및 AAB 번들 통합 빌드를 시작합니다..."
    Push-Location $ProjectRoot
    try {
        & $gradlew assembleDebug assembleRelease bundleRelease --console=plain
        if ($LASTEXITCODE -eq 0) {
            Write-Host "[완료] 빌드가 완료되어 dist 폴더에 최신 APK와 AAB가 저장되었습니다." -ForegroundColor Green
        } else {
            Write-Host "[오류] Gradle 빌드 중 오류가 발생했습니다 (종료 코드: $LASTEXITCODE)." -ForegroundColor Red
        }
    } finally {
        Pop-Location
    }
}

switch ($Action) {
    "InstallLatestApk" { Invoke-InstallLatestApk; exit }
    "GrantPermissions" { Invoke-GrantPermissions; exit }
    "BuildApkAndAab"   { Invoke-BuildApkAndAab; exit }
    "OpenManual"       {
        $htmlManual = Join-Path $DistDir "사용방법_매뉴얼.html"
        if (Test-Path $htmlManual) { Start-Process $htmlManual }
        exit
    }
    "OpenAssets"       {
        $assetsPath = Join-Path $DistDir "에셋등록관련자료"
        if (Test-Path $assetsPath) { Start-Process explorer.exe $assetsPath }
        exit
    }
    "OpenDist"         { Start-Process explorer.exe $DistDir; exit }
}

while ($true) {
    Clear-Host
    $latestApk = Get-LatestInstallApk
    $latestAab = Get-LatestAab
    $apkInfo = if ($latestApk) { "$($latestApk.Name) ($([math]::Round($latestApk.Length / 1MB, 2)) MB)" } else { "없음" }
    $aabInfo = if ($latestAab) { "$($latestAab.Name) ($([math]::Round($latestAab.Length / 1MB, 2)) MB)" } else { "없음" }

    Write-Host "================================================================"
    Write-Host "  나의일상다이어리 (LifeLog) 통합 배포 및 관리 스크립트"
    Write-Host "================================================================"
    Write-Host "  산출물 경로 : $DistDir"
    Write-Host "  최신 APK    : $apkInfo"
    Write-Host "  최신 AAB    : $aabInfo"
    Write-Host "----------------------------------------------------------------"
    Write-Host "  [1] 스마트폰에 최신 APK 설치 및 앱 실행 (ADB)"
    Write-Host "  [2] 스마트폰 필수 권한 일괄 허용 및 배터리 제한 해제"
    Write-Host "  [3] 신규 APK + AAB 통합 빌드 실행 (dist 자동 저장)"
    Write-Host "  [4] 사용방법 매뉴얼(HTML) 열기"
    Write-Host "  [5] 에셋등록관련자료 폴더 열기"
    Write-Host "  [6] dist 산출물 폴더 열기"
    Write-Host "  [0] 종료"
    Write-Host "================================================================"
    $choice = Read-Host "원하는 작업 번호를 입력하세요"

    switch ($choice) {
        "1" { Invoke-InstallLatestApk; Pause }
        "2" { Invoke-GrantPermissions; Pause }
        "3" { Invoke-BuildApkAndAab; Pause }
        "4" {
            $htmlManual = Join-Path $DistDir "사용방법_매뉴얼.html"
            if (Test-Path $htmlManual) { Start-Process $htmlManual } else { Write-Host "매뉴얼 파일이 없습니다."; Pause }
        }
        "5" { Start-Process explorer.exe (Join-Path $DistDir "에셋등록관련자료") }
        "6" { Start-Process explorer.exe $DistDir }
        "0" { break }
        default { Write-Host "올바른 번호를 입력해 주세요." -ForegroundColor Yellow; Start-Sleep -Seconds 1 }
    }
}
