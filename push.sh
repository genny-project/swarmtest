#!/bin/bash

if [ -z "${1}" ]; then
   version="latest"
else
   version="${1}"
fi


docker push gennyproject/swarmtest:"${version}"
docker tag -f gennyproject/swarmtest:"${version}"  gennyproject/swarmtest:latest
docker push gennyproject/swarmtest:latest

