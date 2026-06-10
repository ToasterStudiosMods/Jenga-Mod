@echo off
set DEST=gradle\wrapper\gradle-wrapper.jar

if exist "%DEST%" (
    echo [setup] gradle-wrapper.jar already exists. Nothing to do.
    pause
    goto :eof
)

echo [setup] Downloading gradle-wrapper.jar...

:: Try 1: Fabric example mod repo (correct branch = 1.20, not 1.20.1)
powershell -NoProfile -Command "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri 'https://github.com/FabricMC/fabric-example-mod/raw/1.20/gradle/wrapper/gradle-wrapper.jar' -OutFile '%DEST%' -UseBasicParsing"
if exist "%DEST%" goto :success

:: Try 2: Gradle's own GitHub repo
echo [setup] Try 1 failed, trying Gradle repo...
powershell -NoProfile -Command "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri 'https://github.com/gradle/gradle/raw/v8.4.0/gradle/wrapper/gradle-wrapper.jar' -OutFile '%DEST%' -UseBasicParsing"
if exist "%DEST%" goto :success

:: Try 3: curl fallback
echo [setup] Try 2 failed, trying curl...
curl -fLo "%DEST%" "https://github.com/FabricMC/fabric-example-mod/raw/1.20/gradle/wrapper/gradle-wrapper.jar"
if exist "%DEST%" goto :success

echo.
echo [setup] ERROR: All attempts failed.
echo.
echo  Open this in your browser and save to %CD%\%DEST%
echo  https://github.com/FabricMC/fabric-example-mod/raw/1.20/gradle/wrapper/gradle-wrapper.jar
echo.
pause
exit /b 1

:success
echo [setup] Done! Now run:
echo   gradlew.bat genSources
echo   gradlew.bat build
pause
