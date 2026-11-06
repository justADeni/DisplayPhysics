# DisplayPhysics

Physics for groups of blocks, shown as **packet-only block displays**: collapse, tumble, shatter or blow up.

You describe the blocks, the ground and how they should start moving. The library moves them as one
solid body or as separate pieces, sends every frame straight from its own thread (bundled, so all
clients apply a frame together), and hands back where each piece came to rest.

Supports Spigot, Paper and Folia 26.1, 26.2, 26.3 and newer (servers using Mojang's class names). Only
the Bukkit API is used; the sole server-internal access is building and sending the packets.
`DisplayPhysics.isSupported()` tells you whether the current server can be used.

```java
DisplayPhysics physics = DisplayPhysics.create(plugin);

// 1. Spawns the displays for the viewers (nothing moves yet). Remove the real blocks once spawned() completes.
Assembly assembly = physics.assemble(world, pieces, Viewers.nearby());

// 2. Let it go. Runs on its own virtual thread.
var settings = new Settings().mode(Mode.explode(Blast.at(x, y, z, 4f)));
assembly.simulate(null, settings, terrain, listener).thenAccept(result -> {
    for (int i = 0; i < result.count(); i++) {
        // result.x(i), y(i), z(i), rotation(i): where piece i settled
    }
    // 3. Spawn items / place blocks there, then remove the displays.
    assembly.release();
});
```

## Modes

| `Mode` | Behaviour |
|--------|-----------|
| `Mode.RIGID` | one solid body that falls, bounces, slides and rolls until it rests |
| `Mode.breakOnImpact(speed)` | solid until it hits something faster than `speed`, then every piece scatters |
| `Mode.FALL_APART` | no solid stage: separate pieces from the first tick that simply collapse |
| `Mode.explode(blast)` | pieces are thrown apart by a `Blast` |

A `Hinge` gives the solid modes a start like a door swinging open: tip over an edge, then let go.

### Explosions

```java
Blast.everything()                                   // every piece, away from the assembly's centre
Blast.at(x, y, z, 4f)                                // only pieces within 4 blocks of a point
Blast.at(location, 4f)                               // same, from a Location

    .force(18f)                                      // launch speed (blocks/s) at the epicenter, fading towards the edge
    .lift(0.3f)                                      // extra upward speed, as a fraction of the launch speed
    .aim(Blast.Aim.cone(0, 0, 1, spread))            // all along one direction, scattered within `spread` radians
    .aim(Blast.Aim.OUTWARD)                          // (default) straight away from the epicenter
    .remainder(Blast.Remainder.FALL)                 // pieces out of reach drop; STAY (default) keeps them as solid ground
```

An aimed blast with a small radius behaves like a projectile hitting a wall: the pieces around the
impact fly forwards in a fan, the rest of the wall stays.

## Types

| Type | Role |
|------|------|
| `Piece` | one block: position, `BlockData`, hard or soft, mass, glow colour |
| `Mode`, `Blast` | what happens to the pieces |
| `Hinge` | optional start for the solid modes |
| `TerrainSource` | thread-safe view of the ground; the library never reads the live world |
| `Settings` | gravity, friction, bounciness, mode, ... |
| `Listener` | per-tick hook for your own sounds, particles and damage |
| `Result` | resting position and rotation of every piece |
| `Viewers` | who sees the displays; newcomers get them mid-flight |
| `XYZ` | packs a block position into one `long` (27 bits x, 9 bits y, 27 bits z) for hash keys and key sets; `location`/`block` unpack all three at once |

Positions are primitives (`x, y, z`); every method that takes them has a `Location` overload that
defers to the primitive one, so nothing allocates on the hot path. Packed longs are for places where the
position is a key, like the terrain cache.

Threading: the simulation never touches the world, chunks or entities. The only entity it reads is a
player's location, through a `Platform` (plain Bukkit, or Paper/Folia's entity scheduler, which is only
loaded where that API exists), so it is safe on Folia. Wait for `assembly.spawned()` before removing
the real blocks.

The physics (`sim` package) has no Bukkit dependency and is covered by headless tests: `./gradlew test`.

## Performance

The engine (`sim.FlatDebris`) keeps every piece in flat arrays (structure of arrays), searches for touching
pairs with a counting-sorted cell grid, and probes the terrain through a dense one-byte-per-voxel grid.
Roughly 90 ms per tick for 10,000 pieces all moving, about 1 s for 100,000 (single thread).

With `--add-modules jdk.incubator.vector` the terrain, force, integration and line-of-sight kernels use the
Vector API (`FloatVector.SPECIES_PREFERRED`, so the widest hardware vectors, with masks for tails). Without
it the scalar kernels give identical results, only slower: `Settings.simd(false)` forces them too. Terrain
contacts are 3-7x faster, forces and integration 4-9x. Benchmarks: `./gradlew jmh`
(`-Pbench=KernelBenchmark`, `-Pkernels=scalar|vector`); results and what was tried in `EXPERIMENTS.md`.
Run the server with C2 (HotSpot's default); the Graal JIT compiles the vector code far worse.

## Occlusion culling

`Settings.occlusion(...)` stops sending a piece to a player who cannot see it: too far away, outside the view
cone (the look direction plus a 110 degree field of view, the worst case in Minecraft), or behind terrain or
other simulated pieces. It is decided per player, a few times a second, by marching lines to five points of each
piece through the terrain grid, and only changes what is sent, never the simulation.
