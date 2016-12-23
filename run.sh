#!/bin/bash

export MAVEN_OPTS="-Xmx2048m -XX:MaxPermSize=1024m -Xdebug -Xrunjdwp:transport=dt_socket,server=y,address=8000,suspend=n"
mvn hpi:run
