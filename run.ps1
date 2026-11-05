param([string]$tag, [string]$bench = "KernelBenchmark.terrain", [string]$counts = "1024,16384,262144", [string]$extra = "")
$a = @("--add-modules=jdk.incubator.vector","-XX:+UseParallelGC","-XX:-UseJVMCICompiler","-Djmh.ignoreLock=true") + ($extra -split ' ' | ? { $_ }) + @("-jar","build/libs/DisplayPhysics-0.1.0-jmh.jar",$bench,"-p","count=$counts","-f","1","-wi","3","-i","4","-w","1","-r","1","-bm","avgt","-tu","ns","-rf","json","-rff","benchmarks/$tag.json")
$p = Start-Process java -ArgumentList $a -NoNewWindow -PassThru -RedirectStandardOutput "benchmarks/$tag.log"
$p.ProcessorAffinity = 0xC0
$p.WaitForExit()
& python -I show.py "benchmarks/$tag.json"
