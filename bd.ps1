param([string]$counts = "1024 16384 262144", [string]$extra = "")
$a = @("--add-modules=jdk.incubator.vector","-XX:+UseParallelGC","-XX:-UseJVMCICompiler") + ($extra -split ' ' | ? { $_ }) + @("-cp","build/libs/DisplayPhysics-0.1.0-jmh.jar","com.github.justadeni.displayphysics.sim.Breakdown") + ($counts -split ' ')
$p = Start-Process java -ArgumentList $a -NoNewWindow -PassThru -RedirectStandardOutput "bd.log" -RedirectStandardError "bd.err"
$p.ProcessorAffinity = 0xC0
$p.WaitForExit()
Get-Content bd.log; Get-Content bd.err | select -First 5
