@echo off
setlocal

set BASE_DIR=%~dp0
set WRAPPER_DIR=%BASE_DIR%.mvn\wrapper
set DIST_DIR=%WRAPPER_DIR%\dists
set MAVEN_VERSION=3.9.11
set MAVEN_DIR=%DIST_DIR%\apache-maven-%MAVEN_VERSION%
set MAVEN_BIN=%MAVEN_DIR%\bin\mvn.cmd
set MAVEN_ZIP=%DIST_DIR%\apache-maven-%MAVEN_VERSION%-bin.zip
set WRAPPER_PROPERTIES=%WRAPPER_DIR%\maven-wrapper.properties

if exist "%MAVEN_BIN%" goto execute

if not exist "%DIST_DIR%" mkdir "%DIST_DIR%"
for /f "tokens=1,* delims==" %%A in ('findstr /b "distributionUrl=" "%WRAPPER_PROPERTIES%"') do set DISTRIBUTION_URL=%%B
if "%DISTRIBUTION_URL%" == "" (
  echo Error: distributionUrl is missing in %WRAPPER_PROPERTIES%
  exit /b 1
)

echo Downloading Apache Maven %MAVEN_VERSION%...
powershell -NoProfile -ExecutionPolicy Bypass -Command "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -UseBasicParsing -OutFile '%MAVEN_ZIP%' '%DISTRIBUTION_URL%'"
if errorlevel 1 exit /b 1

powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -Path '%MAVEN_ZIP%' -DestinationPath '%DIST_DIR%' -Force"
if errorlevel 1 exit /b 1

:execute
call "%MAVEN_BIN%" -f "%BASE_DIR%pom.xml" %*
