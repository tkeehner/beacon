@echo off
rem Needs Python 3.9+ with the `cryptography` package (Emit's bundled PC Python has it).
rem Set BEACON_PYTHON to pick a specific interpreter; otherwise `python` on PATH is used.
if not defined BEACON_PYTHON set "BEACON_PYTHON=python"
"%BEACON_PYTHON%" "%~dp0beacon_update.py" %*
