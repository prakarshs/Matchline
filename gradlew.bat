@echo off
rem MATCHLINE: download and verify the official Gradle 8.14.3 wrapper JAR on first use.
setlocal
set "APP_HOME=%~dp0"
set "WRAPPER_JAR=%APP_HOME%gradle\wrapper\gradle-wrapper.jar"
if not exist "%WRAPPER_JAR%" (
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%APP_HOME%gradle\wrapper\bootstrap-wrapper.ps1"
    if errorlevel 1 exit /b 1
)
if defined JAVA_HOME (
    set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
) else (
    set "JAVA_EXE=java.exe"
)
"%JAVA_EXE%" -Dorg.gradle.appname=gradlew -classpath "%WRAPPER_JAR%" org.gradle.wrapper.GradleWrapperMain %*
exit /b %ERRORLEVEL%
