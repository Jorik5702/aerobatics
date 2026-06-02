@echo off
setlocal

set BASE_DIR=%~dp0
set WRAPPER_DIR=%BASE_DIR%.mvn\wrapper
set WRAPPER_JAR=%WRAPPER_DIR%\maven-wrapper.jar
set WRAPPER_PROPERTIES=%WRAPPER_DIR%\maven-wrapper.properties

if not "%JAVA_HOME%" == "" goto javaHomeSet
set JAVACMD=java
goto runWrapper

:javaHomeSet
set JAVACMD=%JAVA_HOME%\bin\java.exe

:runWrapper
if exist "%WRAPPER_JAR%" goto execute
for /f "tokens=1,* delims==" %%A in ('findstr /b "wrapperUrl=" "%WRAPPER_PROPERTIES%"') do set WRAPPER_URL=%%B
powershell -NoProfile -ExecutionPolicy Bypass -Command "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -UseBasicParsing -OutFile '%WRAPPER_JAR%' '%WRAPPER_URL%'"
if errorlevel 1 exit /b 1

:execute
"%JAVACMD%" -Dmaven.multiModuleProjectDirectory="%BASE_DIR%" -jar "%WRAPPER_JAR%" %*
