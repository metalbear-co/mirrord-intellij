@echo off
set JAVA_HOME=C:\Program Files\Java\jdk-20
call "%~dp0gradlew.bat" ktlintCheck %*
