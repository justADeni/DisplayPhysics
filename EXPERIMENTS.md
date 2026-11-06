# Performance notes

The engine (`sim.FlatDebris`) keeps pieces in flat arrays, finds touching pairs with a counting-sorted cell grid and
probes terrain through a dense one-byte-per-voxel grid: about 90 ms per tick for 10,000 moving pieces, about 1 s for
100,000 (single thread). The SIMD kernels in `sim.kernels` run the terrain, force, integration and line-of-sight
passes 3-9x faster than `ScalarKernels`; without the Vector API the scalar kernels give the same results.

`Occlusion` (via `Settings.occlusion`) is decided per player a few times a second. It marches lines to five points of each
piece through the terrain grid and only changes what is sent, never the simulation. The view cone uses the vertical FOV,
the window's aspect ratio and a margin.

## Layout

- `Bodies`: structure-of-arrays state, awake pieces in slots `[0, awake)`.
- `Grid`: dense `byte[]` terrain, one byte per voxel. The low 2 bits hold the type (`TYPE_MASK`); solid voxels also carry
  face bits `OPEN_PX/NX/PY/NY/PZ/NZ` (4..128) for neighbours that are not solid. A probe is one fetch.
- `sim.kernels.Kernels` is the interface, `ScalarKernels` the reference that defines the semantics, `VectorKernels` and
  `VectorVisibility` the SIMD versions. `FlatDebris` calls them 4 substeps per tick: `forces`, pairs (scalar), `terrain`,
  `integrate`, then `calm`.
- `Kernels.vector()` loads `com.github.justadeni.displayphysics.sim.kernels.VectorKernels` by name when the module is
  present. The class sits in the main source tree and is compiled with `--add-modules=jdk.incubator.vector`.
- `terrain`, per piece: the 8 cube corners rotated by the quaternion, each probed in the grid. A corner inside a solid
  voxel picks the nearest open face it could have entered through (a contact: one-hot axis, sign, depth). Then sequential
  impulses (normal first, then the 2 friction directions, which are the other two axes) for `ITERATIONS` rounds, then a
  split-impulse pass. The simplifications in `ScalarKernels.terrain` are intentional: axis-aligned normals, one-hot
  tangents, effective mass `invMass + invInertia*(3C^2 - (r.d)^2)`.

## Rules the kernels follow

- Species are `FloatVector.SPECIES_PREFERRED` and `IntVector.SPECIES_PREFERRED`. No width is hard-coded (the test CPU has
  16 lanes). Tails use masks (`indexInRange`), not a scalar loop.
- Multiply-adds use `Vector.fma`. Approximations are fine below about 1e-3 relative error when the physics stays stable
  (bit-trick rsqrt plus Newton, Taylor normalisation, reciprocal estimates).
- Scratch arrays are reused; hot paths do not allocate, and hot vectors must not be boxed.
- `./gradlew test` must pass, including `KernelEquivalenceTest` (vector against scalar after one tick within 1e-3, odd
  piece count) and the settle test. The scalar semantics do not change.
- Benchmark on HotSpot C2. The development JDK is GraalVM, whose default JIT is poor at the Vector API, so the `jmh {}`
  block in `build.gradle.kts` passes `-XX:-UseJVMCICompiler` and `--add-modules=jdk.incubator.vector`.

## Benchmarking

- `KernelBenchmark` runs each kernel alone, scalar against vector, at sizes 64 to 262144. `FlatDebrisBenchmark` runs the
  whole engine at 1k, 10k and 100k pieces, one simulated second per operation. Select with `-Pbench=KernelBenchmark
  -Pkernels=vector`, or build the jar once (`./gradlew jmhJar`) and run it directly.
- Pin the benchmark process to one CPU when other work runs on the machine (Windows: `cmd /c start /wait /b /affinity
  <HEXMASK> java ...`). Single-threaded runs only, and measure scalar and vector in the same session.
- Raw JMH output is in `benchmarks/`.

## What was learned

- The first attempt (chunked probe, scratch arrays, solve; 7 gathers per corner) ran at 310 ns/piece against 106 for
  scalar on C2, and 40x slower on the Graal JIT. The simple kernels vectorised well: `forces` 2-3x and `integrate` 3-5x
  faster than scalar when cache resident. The cost was in packing and unpacking, the gathers, the scratch round trips,
  masks everywhere, and JIT limits (big methods, vectors boxed across loops and branches).
- Idle lanes are a real cost: a flying piece has no contact, a resting one has about 4 of 8 corners in contact.

## The merged kernel

`VectorKernels` combines three earlier branches: the chunked structure (small static passes talking through scratch rows,
a NEAR cull, pieces listed by contact count), the hybrid's rule of looking only at what can be touched, and the compact
branch's compress step for candidates. It adds:

- `Grid.nbr`: per voxel, a 27-bit mask of which surrounding voxels are solid with an open face (only those can give a
  contact). Zero means nothing within one voxel, which is the cull. Otherwise one gather per piece replaces the eight
  corner gathers, and a corner becomes a bit test. Pieces are listed by how many corners are solid (k), and the way out
  of the voxel is chosen once per contact slot on dense lanes (`locate`, `select`, `derive`) instead of for all 8 corners
  of every piece. A lane whose exposed voxel offers no way out gets a dead slot (all factors zero), as in scalar.
- Corner offsets and floors are evaluated exactly as in `ScalarKernels` (`(+-c0 +-c1) +- c2`, floor on absolute
  coordinates), so corners on a voxel face fall the same way. Before this, 2 of 20011 resting pieces differed by 0.13.
- The iterate step computes the contact point velocity once. An impulse along axis e changes its component along f by
  `-invInertia*j*(r.e)*(r.f)`, exact for orthogonal axes, so v and w are updated once per step instead of three times.
- `CONTEXTS` (3) group vectors are solved side by side, because the dependency chains are latency bound.
- Candidates (index, q, p) are compressed in the cull, and the group vectors gather their state from the arrays.
- `Bodies` arrays are staggered by 0 to 15 cache lines of padding. At 1024 or 16384 pieces every array is a multiple of
  4 KB, and the 30 streams of a kernel alias in the L1/L2 sets. Staggering took integrate from 3.2 to 1.2 ns/piece and
  forces from 0.8 to 0.4.
- `integrate` and `forces` have one method body per full vector (a helper taking vectors is not inlined and boxes), a
  masked twin for the tail, hoisted negations, the Taylor step and bit-trick inverse square root from the scalar kernel,
  and a separate clamp path. `forces` skips the buoyancy lookup when `Grid.liquid` is false.
- Not used: compact's expand write-back and per-lane slot compaction (grouping by k already fills the lanes, 7x on
  resting pieces), and a per-chunk density switch (there was nothing to switch to).

Terrain ns/piece, 1 thread on CPU 20, C2, mixed 16384 / resting 16384 (scalar: 64 / 549):

| Step | mixed / resting |
|------|-----------------|
| first merge (vectors boxed: `floor` helper not inlined, `contact` too big for C2) | 34.5 / 127.7 |
| helpers inlined | 21.0 / 106.7 |
| `contact` split into locate/select/derive | 19.6 / 99.2 |
| two contexts | 19.3 / 86.2 |
| coupled impulse step | 19.3 / 76.4 |
| neighbourhood-mask probe, dense candidates | 17.3 / 73.3 |
| staggered arrays | 17.8 / 72.1 |
| three contexts | 17.0 / 73.4 |

## Vectorised visibility (occlusion)

`VectorVisibility` runs the distance and view-cone tests for a vector of pieces at once. It then marches the lines to the
five sample points together, one grid sample per step for every lane, with a software byte gather and lane sets kept as
`long` bit sets. It matches the scalar kernel (`VisibilityTest.vectorKernelsAgree`).

`KernelBenchmark.visibility` on C2 and one pinned CPU, in ms per call (scalar / vector):

| Pieces | Scalar | Vector |
|--------|--------|--------|
| 4096 | 0.93 | 0.71 |
| 16384 | 6.7 | 5.5 |
| 65536 | 38 | 35 |
| 262144 | 47 | 90 |

The gain is about 1.2x at best. The byte gather is scalar, and the lanes of a batch march for as many steps as the longest
line in it. The benchmark keeps pieces in random memory order, so lane distances differ wildly, which explains the loss
at 262144. The engine keeps pieces in spatial order (periodic reorder), so neighbouring lanes have similar lines there.
It was not pursued further: occlusion runs per viewer at a reduced rate and is a small part of a tick.
