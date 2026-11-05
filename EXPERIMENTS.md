# SIMD kernel experiments

Goal: a `VectorKernels` (Vector API) that is **at least 2x faster than `ScalarKernels` on the `terrain` kernel at some
batch size** (and not slower anywhere that matters), then the same in the whole engine (`FlatDebrisBenchmark`).

## What exists (commit 612a562 on `main`)

- `Bodies`: structure-of-arrays state, awake pieces in slots `[0, awake)`.
- `Grid`: dense `byte[]` terrain, one byte per voxel: type in the low 2 bits (`TYPE_MASK`), and for solid voxels the
  face bits `OPEN_PX/NX/PY/NY/PZ/NZ` (4..128) marking neighbours that are not solid. A probe is ONE fetch.
- `Kernels` (interface), `ScalarKernels` (reference, defines the semantics), `FlatDebris` (the engine, calls the kernels
  4 substeps per tick: `forces`, pairs (scalar), `terrain`, `integrate`; then `calm`).
- `Kernels.vector()` loads `com.github.justadeni.displayphysics.sim.VectorKernels` by name if the module is present. That
  class lives in `src/vector/java/.../sim/VectorKernels.java` (own source set, compiled with
  `--add-modules=jdk.incubator.vector`). **It does not exist on `main` right now: your job is to write it.**
- `terrain` = per piece: 8 cube corners rotated by the quaternion, each probed in the grid; a corner inside a solid
  voxel picks the nearest open face it could have entered through (contact: one-hot axis, sign, depth); then sequential
  impulses (normal first, then 2 friction directions = the other two axes), `ITERATIONS` rounds, then a split-impulse pass.
  Read `ScalarKernels.terrain` closely: every simplification there (axis-aligned normals, one-hot tangents,
  `invMass + invInertia*(3C^2 - (r.d)^2)` effective mass) is intentional.

## What we learned already

- First attempt (chunked probe -> scratch arrays -> solve, 7 gathers per corner): on C2 **310 ns/piece vs 106 scalar**
  (3x slower); on the Graal JIT 40x slower. The simple kernels vectorised fine: `forces` 2-3x, `integrate` 3-5x faster
  than scalar when cache resident. So the SIMD is not the problem: the packing/unpacking, the gathers, the scratch
  round trips, masks everywhere and (maybe) JIT limits (big methods, vector boxing across loops/branches) are.
- The grid was since changed to one fetch per probe (open-face bits), the solver to 3 iterations, tangents to one-hot axes,
  quaternion renormalisation to a Taylor step. All of that is already in `ScalarKernels`; mirror it.
- Idle lanes are a real cost: a flying piece has no contact, a resting one has ~4 of 8 corners in contact.

## Rules

- Species: `FloatVector.SPECIES_PREFERRED` / `IntVector.SPECIES_PREFERRED`. **No hard-coded widths** (this CPU is 16 lanes,
  but nothing may assume it). Tails with masks (`indexInRange`), not a scalar tail loop.
- Use `Vector.fma(...)` for multiply-adds. Approximations are welcome where they cost <~1e-3 relative error and the physics
  stays stable (bit-trick rsqrt + Newton, Taylor normalisation, reciprocal estimates...).
- Reuse arrays (thread-local scratch, no per-call allocation in hot paths). Don't let hot vectors get boxed (check!).
- Correctness gate: `./gradlew test` must pass, including `KernelEquivalenceTest` (vector vs scalar after one tick within
  1e-3, odd piece count) and the settle test. Do not change the scalar semantics. You may ADD fields/helpers (e.g. an
  `int[]` copy of the grid for hardware gathers) but say so in your report.
- JVM: this machine's JDK is GraalVM, whose default JIT (Graal) is poor at the Vector API. **Benchmark on HotSpot C2:**
  `-XX:-UseJVMCICompiler`, plus `--add-modules=jdk.incubator.vector` (the `jmh {}` block in `build.gradle.kts` already
  passes both). You may add `-XX:` flags (e.g. inline/node limits, `-XX:MaxVectorSize`) but report which and the gain,
  and prefer code that is fast without them.

## Benchmarking

- `src/jmh/.../KernelBenchmark` (each kernel alone, scalar vs vector, sizes 64 .. 262144) and `FlatDebrisBenchmark`
  (whole engine, 1k/10k/100k, one simulated second per op). Select with `-Pbench=KernelBenchmark -Pkernels=vector`
  or build the jar once (`./gradlew jmhJar`) and run it directly.
- Several experiments run at the same time on this 16-core machine. **Pin your benchmark process to your assigned CPUs**
  (Windows: `cmd /c start /wait /b /affinity <HEXMASK> java ...`, or PowerShell `Start-Process` + `ProcessorAffinity`) so
  you neither disturb nor are disturbed. Single-threaded runs only. Always measure scalar and vector in the same session.
- Work in your own git worktree:
  `git -C C:/Users/Deni/IdeaProjects/DisplayPhysics worktree add C:/Users/Deni/IdeaProjects/DisplayPhysics-exp/<name> -b exp/<name> 612a562`
  Commit your work on that branch. Save JMH JSON under `benchmarks/` in your worktree.
- Iterate: measure, profile (`-XX:+PrintCompilation`, `-XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining`, look for
  `COMPILE SKIPPED` and allocations), change one thing, measure again. Keep a log of what each change did to ns/piece.
