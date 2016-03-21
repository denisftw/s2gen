#!/bin/bash

DESTINATION_DIR=~/DevTools/s2gen

sbt clean universal:stage

COMPILATION_SUCCESSFUL=$?

if [ "$COMPILATION_SUCCESSFUL" == "0" ]; then
    rm -rf $DESTINATION_DIR
    mkdir $DESTINATION_DIR
    cp -R target/universal/stage/. $DESTINATION_DIR
    echo "A new version of s2gen was installed"
    exit
else
    echo "Compilation failed"
fi

