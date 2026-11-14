@echo off
setlocal
cd /d "%~dp0"
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
set "IP=192.168.101.6:5555"
set "APK=%~dp0RECUPERAR_010\ClickTV010.apk"
if not exist "%ADB%" (echo ERROR: Falta ADB. & pause & exit /b 1)
if not exist "%APK%" (echo ERROR: Falta APK recuperacion. & pause & exit /b 1)
"%ADB%" connect %IP%
"%ADB%" -s %IP% get-state
if errorlevel 1 (echo ERROR: Sin ADB. NO desinstalar ni borrar datos. & pause & exit /b 1)
echo SOLO SI 012 FALLA Y DESEAS VOLVER A LA 010.
echo Se intenta downgrade -r -d; si el firmware rechaza, NO desinstalar.
choice /M "Intentar recuperar Click TV 010 conservando datos"
if errorlevel 2 (echo CANCELADO. & pause & exit /b 1)
"%ADB%" -s %IP% install -r -d "%APK%"
if errorlevel 1 (echo NO SE PUDO RESTAURAR. No desinstalar: guardar error. & pause & exit /b 1)
echo 010 reinstalada. Validar programacion y HOME.
pause
