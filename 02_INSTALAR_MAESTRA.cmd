@echo off
setlocal
cd /d "%~dp0"
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
set "IP=192.168.101.6:5555"
set "APK=%~dp0app\build\outputs\apk\debug\app-debug.apk"
if not exist "%ADB%" (echo ERROR: Falta ADB. & pause & exit /b 1)
if not exist "%APK%" (echo ERROR: Compilar Click TV 012 primero. & pause & exit /b 1)
"%ADB%" connect %IP%
"%ADB%" -s %IP% get-state
if errorlevel 1 (echo ERROR: Maestra no autorizada. & pause & exit /b 1)
echo === VERSION INSTALADA ANTES ===
"%ADB%" -s %IP% shell dumpsys package com.tvnetsolutions.jrplayer | findstr /C:"versionName="
echo === HOME ANTES ===
"%ADB%" -s %IP% shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
echo.
echo ADVERTENCIA: Si NO es la maestra 192.168.101.6, CANCELAR.
echo La version 010 es la base estable; 012 ES SOLO PRUEBA.
echo No hacer cambios en las diez cajas.
choice /M "Actualizar a Click TV 012 SIN borrar datos"
if errorlevel 2 (echo CANCELADO. & pause & exit /b 1)
"%ADB%" -s %IP% install -r "%APK%"
if errorlevel 1 (echo ERROR: Instalacion rechazada. No desinstalar ni borrar datos. & pause & exit /b 1)
echo === VERSION DESPUES ===
"%ADB%" -s %IP% shell dumpsys package com.tvnetsolutions.jrplayer | findstr /C:"versionName="
echo Instalada. Verificar canales y menu MENU x5 ANTES de reiniciar.
pause
