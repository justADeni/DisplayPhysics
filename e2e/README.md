# End-to-end test: real server + real (bot) player

`e2e.mjs` starts a real Spigot, Paper or Folia server with a plugin that uses DisplayPhysics
(Logically), joins it as a bot player, builds a tree, chops it with an axe, and records the packets
the client receives. It checks that:

- the block displays reach the client (and are the tree's blocks),
- they appear **before** the real blocks vanish (no gap),
- they move (pose packets, translation of several blocks) with an even frame cadence,
- every display is removed again, and drops spawn,
- the server log stays clean.

```
node e2e.mjs <spigot|paper|folia> <26.1.2|26.2|26.3>      # needs jars/<platform>-<version>.jar
RAW=1 node e2e.mjs paper 26.3                              # bare protocol client, see below
bash matrix_all.sh                                         # the whole 3x3 matrix
python show.py paper-26.2                                  # read results/<name>.json
```

## Setup (not checked in)

- `jars/`: `paper-<v>.jar`, `folia-<v>.jar` (PaperMC downloads) and `spigot-<v>.jar` (BuildTools `--rev <v>`).
- `npm i mineflayer minecraft-data minecraft-protocol` in this folder.
- The bot's libraries only know 26.1. For **26.2** and **26.3** the protocol data comes from the
  upstream branches `pc_26_2` / `pc_26_3` of PrismarineJS/minecraft-data (`patch4.py`), and 26.3's data
  is then corrected with the packet-id shifts read out of the server jar (`idiff.py` + `patch5.py`).
- 26.3 also changed what the client has to send, handled in `e2e.mjs`'s raw mode (`RAW=1`):
  teleport confirmations carry the position, "finish digging" is action 3 (a new
  `CHANGE_DESTROY_DIRECTION` was inserted), and the swing packet became an empty `punch`.

## Folia

Console commands that touch entities don't work on Folia, so the bot runs every setup command as the
operator it becomes (the command then runs on the player's own thread).
