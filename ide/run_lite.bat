@echo off
java -Xmx256m -Xms64m -Dfile.encoding=UTF-8 -cp "%~dp0..\mvm\target;%~dp0target" ide.MishaLiteIDE
pause
