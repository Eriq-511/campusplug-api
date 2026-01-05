@ECHO OFF
SETLOCAL

SET "BASEDIR=%~dp0"
IF "%BASEDIR:~-1%"=="\" SET "BASEDIR=%BASEDIR:~0,-1%"
SET "WRAPPER_JAR=%BASEDIR%\.mvn\wrapper\maven-wrapper.jar"

IF NOT EXIST "%WRAPPER_JAR%" (
  ECHO Maven wrapper jar missing. Downloading...
  POWERSHELL -NoProfile -ExecutionPolicy Bypass -Command "New-Item -ItemType Directory -Force -Path '%BASEDIR%\.mvn\wrapper' | Out-Null; Invoke-WebRequest -Uri 'https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.2.0/maven-wrapper-3.2.0.jar' -OutFile '%BASEDIR%\.mvn\wrapper\maven-wrapper.jar'"
  IF ERRORLEVEL 1 (
    ECHO Failed to download Maven wrapper jar.
    EXIT /B 1
  )
)

SET "JAVA_EXE=java"
IF NOT "%JAVA_HOME%"=="" (
  IF EXIST "%JAVA_HOME%\bin\java.exe" (
    SET "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
  )
)

"%JAVA_EXE%" -classpath "%WRAPPER_JAR%" -Dmaven.multiModuleProjectDirectory="%BASEDIR%" org.apache.maven.wrapper.MavenWrapperMain %*

ENDLOCAL
