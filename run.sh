#!/bin/bash
# usage: run.sh tag [benchmark regex]
tag=$1; b=${2:-KernelBenchmark.terrain}
cmd /c start /wait /b /affinity C0 java --add-modules=jdk.incubator.vector -XX:+UseParallelGC -XX:-UseJVMCICompiler -jar build/libs/DisplayPhysics-0.1.0-jmh.jar "$b" -p count=1024,16384,262144 -f 1 -wi 3 -i 4 -w 1 -r 1 -tu ns -rf json -rff benchmarks/$tag.json 2>&1 | grep -E "^Benchmark|^Kernel|Exception|Error"
