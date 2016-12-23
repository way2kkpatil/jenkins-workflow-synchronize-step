#!/bin/bash

mvn -DdownloadSources=true -DdownloadJavadocs=true -DoutputDirectory=target/eclipse-classes -Declipse.workspace=/Users/kamleshpatil/Eclipse-Workspaces/Jenkins eclipse:eclipse eclipse:configure-workspace