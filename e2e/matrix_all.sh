#!/bin/bash
cd "$(dirname "$0")"
bash matrix.sh "paper:26.1.2 folia:26.1.2 spigot:26.1.2 paper:26.2 folia:26.2 spigot:26.2"
RAW=1 bash matrix.sh "paper:26.3 folia:26.3 spigot:26.3"
echo EVERYTHING DONE
