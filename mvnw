#!/bin/sh
# ----------------------------------------------------------------------------
# Maven Start Up Batch script
#
# Required ENV vars:
# ------------------
# JAVA_HOME - location of a JDK home dir
# ----------------------------------------------------------------------------

# Language setting
export LANG=en_US.UTF-8

# Execute Maven Wrapper
exec java -jar .mvn/wrapper/maven-wrapper.jar "$@"
