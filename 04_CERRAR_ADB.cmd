@echo off
setlocal
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not exist "%ADB%" (echo ERROR: Falta ADB. & pause & exit /b 1)
"%ADB%" disconnect 192.168.101.6:5555
"%ADB%" kill-server
echo ADB desconectado. Ahora hacer prueba real de reinicio por control remoto.
pause
