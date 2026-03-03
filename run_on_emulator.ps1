
$EMULATOR_NAME = "GeelyEX5"
# $EMULATOR_NAME = "arm_tablet"
$ADB_PATH = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$EMULATOR_PATH = "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe"
$PROJECT_ROOT = $PSScriptRoot
$PACKAGE_NAME = "com.yahooeu2k.dlb_charging"
$ACTIVITY_NAME = "$PACKAGE_NAME.MainActivity"

# 1. Check if emulator is running and pick one
Write-Host "Checking for running emulators..."
$devicesOutput = & $ADB_PATH devices
$emulatorMatch = $devicesOutput | Select-String -Pattern "(emulator-\d+)"
$deviceId = $null

if ($emulatorMatch) {
    $deviceId = $emulatorMatch.Matches[0].Value
    Write-Host "Found running emulator: $deviceId"
} else {
    Write-Host "No running emulator found. Starting '$EMULATOR_NAME'..."
    Start-Process -FilePath $EMULATOR_PATH -ArgumentList "-avd $EMULATOR_NAME" -NoNewWindow
    
    Write-Host "Waiting for emulator to become ready..."
    & $ADB_PATH wait-for-device
    
    # Get the device ID after starting
    $devicesOutput = & $ADB_PATH devices
    $emulatorMatch = $devicesOutput | Select-String -Pattern "(emulator-\d+)"
    if ($emulatorMatch) {
        $deviceId = $emulatorMatch.Matches[0].Value
        Write-Host "Emulator started: $deviceId"
    } else {
        Write-Warning "Could not determine emulator ID automatically. Will try without specific ID."
    }
}

# 2. Build the debug APK
Write-Host "Building Debug APK..."
Set-Location $PROJECT_ROOT
./gradlew.bat assembleDebug

if ($LASTEXITCODE -ne 0) {
    Write-Error "Build failed!"
    exit 1
}

# 3. Install the APK
$apkOutputDir = "$PROJECT_ROOT\app\build\outputs\apk\debug"
$apkPath = Get-ChildItem -Path $apkOutputDir -Filter "*.apk" | Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName

if (-not $apkPath) {
    Write-Error "No APK found in $apkOutputDir"
    exit 1
}

Write-Host "Found APK: $apkPath"

Write-Host "Installing APK..."
if ($deviceId) {
    & $ADB_PATH -s $deviceId install -r $apkPath
} else {
    & $ADB_PATH install -r $apkPath
}

# 4. Launch the App
Write-Host "Launching App..."
if ($deviceId) {
    & $ADB_PATH -s $deviceId shell am start -n "$PACKAGE_NAME/$ACTIVITY_NAME"
} else {
    & $ADB_PATH shell am start -n "$PACKAGE_NAME/$ACTIVITY_NAME"
}
