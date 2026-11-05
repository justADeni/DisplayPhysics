# DisplayPhysics

Rigid-body and debris physics for a group of blocks, shown as **packet-only block displays**.

You describe the blocks, the ground and how it should start moving. The library moves the
blocks as one solid unit, optionally breaks it into single blocks when it hits something too
fast, sends every frame straight from its own thread (bundled, so all clients apply a frame
together), and hands back where each block came to rest.

Supports Spigot, Paper and Folia 26.1, 26.2, 26.3 and newer (servers using Mojang's class names). Only the Bukkit API is used; the sole server-internal access is building and sending the packets. `DisplayPhysics.isSupported()`
tells you whether the current server can be used.

```java
DisplayPhysics physics = DisplayPhysics.create(plugin);

// 1. Spawns the displays for the viewers (nothing moves yet). Remove the real blocks a tick later.
Assembly assembly = physics.assemble(world, pieces, Viewers.nearby());

// 2. Let it fall. Runs on its own virtual thread.
assembly.simulate(hinge, new Settings().breakMode(BreakMode.BREAK_ON_IMPACT).breakSpeed(6f), terrain, listener)
        .thenAccept(result -> {
            for (int i = 0; i < result.count(); i++) {
                // result.x(i), y(i), z(i), rotation(i): where piece i settled
            }
            // 3. Spawn items / place blocks there, then remove the displays.
            assembly.release();
        });
```

| Type | Role |
|------|------|
| `Piece` | one block: position, `BlockData`, hard (logs) or soft (leaves), mass, glow colour |
| `Hinge` | optional start: tip over an edge like a felled tree, then let go |
| `TerrainSource` | thread-safe view of the ground; the library never reads the live world |
| `Settings` / `BreakMode` | gravity, friction, bounciness, `RIGID` or `BREAK_ON_IMPACT` with `breakSpeed` |
| `Listener` | per-tick hook for your own sounds, particles and damage |
| `Result` | resting position and rotation of every piece |
| `Viewers` | who sees the displays; newcomers get them mid-fall |

Threading: the simulation never touches the world, chunks or entities. The only entity it reads is a player's location, through a `Platform` (plain Bukkit, or Paper/Folia's entity scheduler, which is only loaded where that API exists), so it is safe on Folia. Wait for `assembly.spawned()` before removing the real blocks. Displays that
nobody releases are removed automatically after the simulation (`Settings.autoReleaseSeconds`).

The physics (`sim` package) has no Bukkit dependency and is covered by headless tests:
`./gradlew test`.
