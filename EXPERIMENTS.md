# SIMD kernel experiments

Goal: a `VectorKernels` (Vector API) that is **at least 2x faster than `ScalarKernels` on the `terrain` kernel at some
batch size** (and not slower anywhere that matters), then the same in the whole engine (`FlatDebrisBenchmark`).

## What exists (commit 1106a5d on `main`)

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
  `git -C C:/Users/Deni/IdeaProjects/DisplayPhysics worktree add C:/Users/Deni/IdeaProjects/DisplayPhysics-exp/<name> -b exp/<name> 1106a5d`
  Commit your work on that branch. Save JMH JSON under `benchmarks/` in your worktree.
- Iterate: measure, profile (`-XX:+PrintCompilation`, `-XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining`, look for
  `COMPILE SKIPPED` and allocations), change one thing, measure again. Keep a log of what each change did to ns/piece.

## exp/merged: the three branches in one kernel

`VectorKernels` = chunked's structure (small static passes talking through scratch rows, NEAR cull, listing by contact
count) + hybrid's "only look at what can be touched" + compact's compress for the candidates, plus new pieces:

- `Grid.nbr`: per voxel a 27-bit mask of which voxels around it are solid *with an open face* (those are the only ones that
  can give a contact). Zero = nothing within one voxel (the cull); otherwise ONE gather per piece replaces the eight corner
  gathers of the probe (a corner is a bit test). Pieces are listed by how many corners that is (k), and the way out of the
  voxel is chosen once per contact slot on dense lanes (`locate` / `select` / `derive`), not for all 8 corners of everything.
  A lane whose exposed voxel offers no way out gets a dead slot (all factors zero), which is exactly what scalar does.
- Corner offsets and floors are evaluated exactly as ScalarKernels does (`(+-c0 +-c1) +- c2`, floor on absolute
  coordinates): corners on a voxel face fall the same way (2 of 20011 resting pieces differed by 0.13 before that).
- The iterate step computes the contact point velocity once; an impulse along axis e changes its component along f by
  `-invInertia*j*(r.e)*(r.f)` (exact for orthogonal axes), so v and w are updated once per step instead of three times.
- `CONTEXTS` (3) group vectors are solved side by side: the chains are latency bound.
- Candidates (index, q, p) are compressed in the cull; the group vectors gather their state from the arrays.
- `Bodies` arrays are staggered by 0..15 cache lines of padding: 1024 / 16384 pieces make every array a multiple of 4 KB
  and the 30 streams of a kernel then alias in the L1/L2 sets (integrate 3.2 ns -> 1.2 ns/piece, forces 0.8 -> 0.4).
- integrate/forces: one method body per full vector (a helper taking vectors is not inlined and boxes), masked twin for
  the tail, hoisted negations, Taylor step and bit-trick inverse square root as in the scalar kernel, clamp path separate.
  forces skips the buoyancy lookup when `Grid.liquid` is false.
- Not used: compact's expand write-back and per-lane slot compaction (grouping by k already fills the lanes: resting 7x),
  a per-chunk density switch (nothing to switch to).

Log (terrain ns/piece, 1 thread on CPU 20, C2, mixed 16384 / resting 16384; scalar 64 / 549):
first merge 34.5 / 127.7 (vectors boxed: `floor` helper not inlined, `contact` too big for C2) -> helpers inlined 21.0 / 106.7
-> contact split in locate/select/derive 19.6 / 99.2 -> two contexts 19.3 / 86.2 -> coupled impulse step 19.3 / 76.4
-> neighbourhood mask probe + dense candidates 17.3 / 73.3 -> staggered arrays 17.8 / 72.1 -> three contexts 17.0 / 73.4.
