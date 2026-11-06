# DisplayPhysics

Physics for groups of blocks, shown as packet-only block displays: collapse, tumble, shatter or blow up.
Works on Spigot, Paper and Folia 26.1 and newer. Check `DisplayPhysics.isSupported()` before use.

## Usage

```java
DisplayPhysics physics = DisplayPhysics.create(plugin);

// 1. Spawn the displays for the viewers. Remove the real blocks once spawned() completes.
Assembly assembly = physics.assemble(world, pieces, Viewers.nearby());

// 2. Let it go. Runs off the main thread.
var settings = new Settings().mode(Mode.explode(Blast.at(x, y, z, 4f)));
assembly.simulate(null, settings, terrain, listener).thenAccept(result -> {
    for (int i = 0; i < result.count(); i++) {
        // result.x(i), y(i), z(i), rotation(i): where piece i settled
    }
    // 3. Place blocks or spawn items there, then remove the displays.
    assembly.release();
});
```

## Modes

| `Mode` | Behaviour |
|--------|-----------|
| `Mode.RIGID` | one solid body that falls, bounces and rolls until it rests |
| `Mode.breakOnImpact(speed)` | solid until it hits something faster than `speed`, then the pieces scatter |
| `Mode.FALL_APART` | separate pieces from the first tick |
| `Mode.explode(blast)` | pieces are thrown apart by a `Blast` |

A `Hinge` gives the solid modes a start like a door swinging open.

## Explosions

```java
Blast.everything()                       // every piece, away from the assembly's centre
Blast.at(x, y, z, 4f)                    // only pieces within 4 blocks of a point
Blast.at(location, 4f)                   // same, from a Location

    .force(18f)                          // launch speed in blocks/s at the epicenter
    .lift(0.3f)                          // extra upward speed, as a fraction of the launch speed
    .aim(Blast.Aim.cone(0, 0, 1, 0.4f))  // all along one direction, within 0.4 radians
    .aim(Blast.Aim.OUTWARD)              // (default) straight away from the epicenter
    .remainder(Blast.Remainder.FALL)     // pieces out of reach drop; STAY (default) keeps them in place
```

## Types

| Type | Role |
|------|------|
| `Piece` | one block: position, `BlockData`, hard or soft, mass, glow colour |
| `Settings` | gravity, friction, bounciness, mode, occlusion, ... |
| `TerrainSource` | thread-safe view of the ground; you implement it |
| `Listener` | per-tick callbacks for sounds, particles and damage |
| `Result` | resting position and rotation of every piece |
| `Viewers` | who sees the displays: `Viewers.nearby()`, `Viewers.within(radius)` |
| `XYZ` | packs a block position into one `long` for hash keys |

Every method that takes `x, y, z` also has a `Location` overload.

## Settings

```java
new Settings()
    .gravity(9.81f)
    .friction(0.6f)
    .bounciness(0.25f)
    .maxDuration(30f)                  // seconds before the simulation stops
    .occlusion(Occlusion.standard())   // skip pieces a player cannot see
    .simd(true);                       // use the Vector API when the JVM has it
```

The Vector API needs `--add-modules jdk.incubator.vector` and C2 (HotSpot's default JIT).
Tests: `./gradlew test`. Benchmarks: `./gradlew jmh`, with notes in `EXPERIMENTS.md`.
