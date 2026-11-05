// End-to-end test: real server + real (bot) player. Usage: node e2e.mjs <spigot|paper|folia> <26.1.2|26.2|26.3>
import { spawn, execFileSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import mineflayer from 'mineflayer'

const [platform, version] = process.argv.slice(2)
const ROOT = path.resolve('..')
const PLUGIN = process.env.PLUGIN_JAR || 'C:/Users/Deni/IdeaProjects/Logically/build/libs/Logically-1.34.2.jar'
const PROTOCOL = version === '26.1.2' ? '26.1' : version
const PORT = 25600 + Math.floor(Math.random() * 300)
const dir = path.join(ROOT, 'servers', `${platform}-${version}`)
const sleep = ms => new Promise(r => setTimeout(r, ms))

fs.rmSync(dir, { recursive: true, force: true })
fs.mkdirSync(path.join(dir, 'plugins', 'Logically'), { recursive: true })
fs.copyFileSync(PLUGIN, path.join(dir, 'plugins', 'Logically.jar'))
fs.writeFileSync(path.join(dir, 'eula.txt'), 'eula=true\n')
fs.writeFileSync(path.join(dir, 'server.properties'), [
  `server-port=${PORT}`, 'online-mode=false', process.env.LEVEL === 'normal' ? 'level-type=minecraft\\:normal' : 'level-type=minecraft\\:flat', 'difficulty=peaceful',
  'spawn-protection=0', 'spawn-monsters=false', 'spawn-animals=false', 'view-distance=6', 'simulation-distance=4',
  'enable-command-block=true', 'white-list=false', 'enforce-whitelist=false', 'gamemode=survival', 'enforce-secure-profile=false', 'max-tick-time=-1', ''
].join('\n'))
// The plugin's bundled default config (it holds the tree definitions) with debug logging on
const bundled = execFileSync('unzip', ['-p', PLUGIN, 'config.yml']).toString()
fs.writeFileSync(path.join(dir, 'plugins', 'Logically', 'config.yml'), bundled.replace(/^debug: false/m, 'debug: true'))

const jar = path.join(ROOT, 'jars', `${platform}-${version}.jar`)
if (!fs.existsSync(jar)) { console.log(JSON.stringify({ platform, version, error: `missing ${jar}` })); process.exit(2) }

const logFile = path.join(dir, 'console.log')
const log = fs.createWriteStream(logFile)
const server = spawn('java', ['-Xmx1536M', '-Dcom.mojang.eula.agree=true', '-jar', jar, 'nogui'], { cwd: dir })
let consoleText = ''
let ready, exited = false
const readyP = new Promise(r => (ready = r))
const onOut = chunk => {
  const s = chunk.toString().replace(/[[0-9;]*m/g, "")
  consoleText += s
  log.write(s)
  if (/Done \(/.test(s)) ready()
}
server.stdout.on('data', onOut)
server.stderr.on('data', onOut)
server.on('exit', () => { exited = true; ready() })
const cmd = c => server.stdin.write(c + '\n')

const result = { platform, version, port: PORT, checks: {}, notes: [] }
const fail = (name, detail) => { result.checks[name] = { ok: false, detail } }
const pass = (name, detail) => { result.checks[name] = { ok: true, detail } }

async function main() {
  await Promise.race([readyP, sleep(240000)])
  if (exited) throw new Error('server exited before it was ready')
  pass('server-start', 'Done')
  const logicallyEnabled = /\[Logically\] Enabling Logically/.test(consoleText)
  logicallyEnabled ? pass('plugin-enabled', '') : fail('plugin-enabled', 'no "Enabling Logically" line')

  if (process.env.PROBE) {
    const mc = (await import('minecraft-protocol')).default
    const client = mc.createClient({ host: '127.0.0.1', port: PORT, username: 'Tester', version: PROTOCOL, auth: 'offline', hideErrors: false })
    const seen = []
    client.on('state', st => { if (st !== 'configuration') return; client.write('settings', { locale: 'en_US', viewDistance: 6, chatFlags: 0, chatColors: true, skinParts: 127, mainHand: 1, enableTextFiltering: false, enableServerListing: true, particleStatus: 'all' }); client.registerChannel('minecraft:brand', ['string', []]); client.writeChannel('minecraft:brand', 'vanilla') })
    client.on('packet', (d, m) => seen.push(`${m.state}:${m.name}`))
    client.on('raw', (buf, m) => seen.push(`raw[${m.state}] id=${buf[0]} len=${buf.length} ${m.name ?? '?'}`))
    client.on('state', st => seen.push(`--> state ${st}`))
    client.on('error', e => seen.push('ERROR ' + String(e.message).slice(0, 160)))
    client.on('disconnect', r => seen.push('DISCONNECT ' + JSON.stringify(r).slice(0, 200)))
    await sleep(15000)
    result.notes.push(seen.join(' | ').slice(0, 6000))
    client.end()
    return
  }
  const RAW = !!process.env.RAW
  let bot
  if (RAW) {
    // For versions whose packet definitions are incomplete: no mineflayer logic, packets that fail to parse are dropped
    const mc = (await import('minecraft-protocol')).default
    const client = mc.createClient({ host: '127.0.0.1', port: PORT, username: 'Tester', version: PROTOCOL, auth: 'offline', hideErrors: true })
    client.on('state', st => { if (st !== 'configuration') return; client.write('settings', { locale: 'en_US', viewDistance: 6, chatFlags: 0, chatColors: true, skinParts: 127, mainHand: 1, enableTextFiltering: false, enableServerListing: true, particleStatus: 'all' }); client.registerChannel('minecraft:brand', ['string', []]); client.writeChannel('minecraft:brand', 'vanilla') })
    // A real client acknowledges every teleport (/tp, spawn); the server ignores a player until it has
    client.on('position', d => { try { client.write('teleport_confirm', { teleportId: d.teleportId, x: d.x, y: d.y, z: d.z, yaw: d.yaw, pitch: d.pitch }) } catch (e) { result.notes.push('teleport_confirm: ' + e.message) } })
    const sent = []; const realWrite = client.write.bind(client); client.write = (n, d) => { sent.push(n); return realWrite(n, d) }; result.sent = sent
    bot = {
      _client: client,
      chat: c => client.write('chat_command', { command: c.replace(/^\//, '') }),
      quit: () => client.end(),
      on: () => {},
      registry: null
    }
  } else {
    bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: 'Tester', version: PROTOCOL, auth: 'offline', hideErrors: false, plugins: { physics: false } })
  }
  const registry = () => bot.registry
  let displayType, itemType
  const events = []   // {t, name, ...}
  let recording = false
  let tChop = 0

  bot.on('error', e => result.notes.push('bot error: ' + e.message))
  bot._client.on('error', e => result.notes.push('client error: ' + e.message))
  await new Promise((res, rej) => {
    if (RAW) {
      bot._client.once('state', () => {})
      const check = setInterval(() => { if (bot._client.state === 'play') { clearInterval(check); setTimeout(res, 2000) } }, 100)
      bot._client.once('disconnect', r => rej(new Error('disconnected: ' + JSON.stringify(r))))
    } else {
      bot.once('spawn', res)
      bot.once('kicked', r => rej(new Error('kicked: ' + JSON.stringify(r))))
    }
    setTimeout(() => rej(new Error('join timeout')), 60000)
  })
  pass('player-joined', `protocol ${RAW ? PROTOCOL + ' (raw client)' : bot.registry.version?.version ?? '?'}`)
  if (!RAW) {
    displayType = registry().entitiesByName.block_display.id
    itemType = registry().entitiesByName.item.id
  }
  const airIds = new Set([0])

  bot._client.on('packet', (data, meta) => {
    if (!recording) return
    const t = Date.now() - tChop
    switch (meta.name) {
      case 'spawn_entity':
        events.push({ t, n: 'spawn', id: data.entityId, type: data.type, x: data.x, y: data.y, z: data.z })
        break
      case 'entity_metadata': {
        const ev = { t, n: 'meta', id: data.entityId }
        for (const m of data.metadata) {
          const v = m.value
          if (v && typeof v === 'object' && 'w' in v) ev.rot = v
          else if (v && typeof v === 'object' && 'x' in v && 'y' in v) ev.tr = v
          if (m.type === 'block_state' || m.type === 'blockstate') ev.state = v
        }
        events.push(ev)
        break
      }
      case 'entity_destroy':
        for (const id of data.entityIds) events.push({ t, n: 'destroy', id })
        break
      case 'block_change':
        events.push({ t, n: airIds.has(data.type) ? 'air' : 'bc', st: data.type, x: data.location.x, y: data.location.y, z: data.location.z })
        break
      case 'multi_block_change': {
        const air = data.records.filter(r => airIds.has(Number(r) >> 12)).length
        if (air >= 5) events.push({ t, n: 'multi', air })
        break
      }
    }
  })

  // Only /op needs the console. Everything else the bot does as the operator it becomes, so the
  // commands run on the player's own thread, which is also the only way they work on Folia.
  cmd('op Tester'); await sleep(1500)
  const loginAt = consoleText.match(/Tester\[[^\]]*\] logged in with entity id \d+ at \(\[[^\]]*\]\s*(-?[\d.]+), (-?[\d.]+), (-?[\d.]+)\)/)
  if (!loginAt) throw new Error('could not read the spawn point from the login line')
  const px = Math.floor(+loginAt[1]), by = Math.floor(+loginAt[2]), pz = Math.floor(+loginAt[3])
  const bx = px + 6, bz = pz
  result.notes.push(`ground y=${by - 1}`)
  const run = async c => { bot.chat('/' + c); await sleep(700) }
  await run('give Tester diamond_axe')
  await run('gamemode survival')
  await run(`fill ${bx} ${by} ${bz} ${bx} ${by + 6} ${bz} minecraft:oak_log`)
  await run(`fill ${bx - 2} ${by + 4} ${bz - 2} ${bx + 2} ${by + 7} ${bz + 2} minecraft:oak_leaves[persistent=false] keep`)
  await run(`tp Tester ${bx - 2} ${by} ${bz}`)
  await sleep(3000)

  if (RAW) {
    pass('tree-built', 'raw client keeps no chunk data; the fill commands were issued')
  } else {
    const Vec3 = bot.entity.position.constructor
    const base = bot.blockAt(new Vec3(bx, by, bz))
    if (!base || base.name !== 'oak_log') throw new Error('test tree not found: ' + (base && base.name))
    const axe = bot.inventory.items().find(i => i.name === 'diamond_axe')
    if (!axe) throw new Error('no axe in inventory')
    pass('tree-built', `${base.name} at ${base.position}, axe in slot ${axe.slot}`)
  }

  if (process.env.DIAG) {
    const from = consoleText.length
    cmd('data get entity Tester playerGameType'); cmd('data get entity Tester Pos'); cmd('data get entity Tester SelectedItemSlot'); cmd('data get entity Tester Inventory'); cmd(`data get block ${bx} ${by} ${bz}`); cmd(`execute if block ${bx} ${by} ${bz} minecraft:oak_log run say LOG_PRESENT`)
    await sleep(2000)
    result.notes.push('diag: ' + consoleText.slice(from).split(String.fromCharCode(10)).filter(l => /Tester has|LOG_PRESENT|log|Found no|block/i.test(l)).join(' || ').slice(0, 1500))
  }

  // The bot has no physics here, so it digs the way a client does on the wire:
  // start, swing, and finish once the break time has passed
  const swing = () => { if (version === '26.3') bot._client.write('new_punch', { data: Buffer.alloc(0) }); else bot._client.write('arm_animation', { hand: 0 }) }
  const loc = { x: bx, y: by, z: bz }
  recording = true
  tChop = Date.now()
  swing() // 26.3 replaced the swing packet by an empty 'punch'
  bot._client.write('block_dig', { status: 0, location: loc, face: 1, sequence: 1 })
  await sleep(1200)
  swing() // 26.3 replaced the swing packet by an empty 'punch'
  // 26.3 inserted CHANGE_DESTROY_DIRECTION, so 'finished digging' moved from 2 to 3
  bot._client.write('block_dig', { status: version === '26.3' ? 3 : 2, location: loc, face: 1, sequence: 2 })

  // wait for the fall: displays spawn, move, vanish
  const deadline = Date.now() + 45000
  while (Date.now() < deadline) {
    await sleep(500)
    // done once the biggest group of spawned entities (the displays) has been removed again
    const counts = {}
    for (const e of events) if (e.n === 'spawn') counts[e.type] = (counts[e.type] || 0) + 1
    const spawned = Math.max(0, ...Object.values(counts))
    const destroyed = events.filter(e => e.n === 'destroy').length
    if (spawned >= 50 && destroyed >= spawned) break
  }
  await sleep(1500)
  recording = false

  // ── Analysis ──
  if (process.env.DEBUG_EVENTS) result.earlyEvents = events.filter(e => e.n !== 'meta').slice(0, 40).map(e => `${e.n}@${e.t} ${e.x ?? ''},${e.y ?? ''},${e.z ?? ''} id=${e.id ?? ''} st=${e.st ?? ''}`)
  const allSpawns = events.filter(e => e.n === 'spawn')
  if (displayType === undefined) {
    // registry unknown (raw client): the block displays are the entity type spawned in the hundreds at once
    const counts = {}
    for (const e of allSpawns) counts[e.type] = (counts[e.type] || 0) + 1
    displayType = +Object.entries(counts).sort((a, b) => b[1] - a[1])[0]?.[0]
    result.notes.push(`display entity type inferred as ${displayType} from ${JSON.stringify(counts)}`)
  }
  const spawns = allSpawns.filter(e => e.type === displayType)
  const displayIds = new Set(spawns.map(e => e.id))
  const metas = events.filter(e => e.n === 'meta' && displayIds.has(e.id))
  const destroys = events.filter(e => e.n === 'destroy' && displayIds.has(e.id))
  const items = itemType === undefined ? [] : allSpawns.filter(e => e.type === itemType)
  // The tree's blocks vanish in one big update. (A cancelled break can flicker the origin block
  // for a tick on Spigot; that is not the tree being removed.)
  const airs = events.filter(e => e.n === 'multi' && e.air >= 20)
  result.counts = { displays: spawns.length, metadataPackets: metas.length, destroyed: destroys.length, items: items.length, airBlockChanges: airs.length }

  spawns.length >= 10 ? pass('displays-spawned', `${spawns.length} block displays reached the client`) : fail('displays-spawned', `only ${spawns.length}`)
  const states = metas.filter(m => m.state !== undefined).length
  result.counts.withBlockState = states
  result.firstRemovals = airs.sort((a, b) => a.t - b.t).slice(0, 4).map(a => `${a.n}@${a.t}ms ${a.x ?? ''},${a.y ?? ''},${a.z ?? ''} air=${a.air ?? 1}`)
  const firstAir = airs.length ? Math.min(...airs.map(a => a.t)) : null
  const firstSpawn = spawns.length ? Math.min(...spawns.map(a => a.t)) : null
  if (firstSpawn !== null && firstAir !== null)
    firstSpawn <= firstAir ? pass('spawn-before-blocks-vanish', `spawn at ${firstSpawn}ms, first block removed at ${firstAir}ms`) : fail('spawn-before-blocks-vanish', `spawn ${firstSpawn}ms > air ${firstAir}ms`)
  else fail('spawn-before-blocks-vanish', `spawn=${firstSpawn} air=${firstAir}`)

  const moving = metas.filter(m => m.tr)
  const maxTr = moving.reduce((m, e) => Math.max(m, Math.hypot(e.tr.x, e.tr.y, e.tr.z)), 0)
  moving.length > 0 && maxTr > 3 ? pass('displays-moved', `${moving.length} pose packets, max translation ${maxTr.toFixed(1)} blocks`) : fail('displays-moved', `${moving.length} pose packets, max translation ${maxTr.toFixed(2)}`)

  // frame cadence: pose packets of one tick arrive together
  const times = [...new Set(moving.map(m => m.t))].sort((a, b) => a - b)
  const frames = []
  for (const t of times) if (!frames.length || t - frames[frames.length - 1] > 15) frames.push(t)
  if (frames.length > 5) {
    const gaps = frames.slice(1).map((t, i) => t - frames[i])
    const mean = gaps.reduce((a, b) => a + b, 0) / gaps.length
    const sd = Math.sqrt(gaps.reduce((a, b) => a + (b - mean) ** 2, 0) / gaps.length)
    result.cadence = { frames: frames.length, meanGapMs: +mean.toFixed(1), stdDevMs: +sd.toFixed(1), maxGapMs: Math.max(...gaps) }
    pass('frame-cadence', JSON.stringify(result.cadence))
  } else fail('frame-cadence', `only ${frames.length} frames`)

  const spawnedIds = new Set(spawns.map(s => s.id))
  const goneIds = new Set(destroys.map(d => d.id))
  const leaked = [...spawnedIds].filter(i => !goneIds.has(i)).length
  leaked === 0 && spawns.length > 0 ? pass('displays-removed', 'all removed') : fail('displays-removed', `${leaked} of ${spawns.length} never removed`)
  items.length > 0 ? pass('drops-spawned', `${items.length} item entities`) : result.notes.push('no item entities seen (drops may be disabled or not yet spawned)')

  // Problems the server logged from the moment the player joined (startup chatter is not ours)
  const afterJoin = consoleText.slice(Math.max(0, consoleText.indexOf('Tester joined')))
  const logProblems = afterJoin.split('\n').filter(l => /Exception|SEVERE|\bERROR\b|\bWARN\b|\/WARN\]|\/ERROR\]/.test(l) && !/Tester lost connection/.test(l))
  logProblems.length === 0 ? pass('server-log-clean', '') : fail('server-log-clean', logProblems.slice(0, 6).join(' | '))
  const settled = consoleText.match(/Physics settled after (\d+) ticks/)
  result.notes.push(settled ? `settled after ${settled[1]} ticks` : 'no "settled" debug line')

  bot.quit()
}

try {
  await main()
} catch (e) {
  fail('harness', e.stack || String(e))
} finally {
  try { cmd('stop') } catch {}
  await Promise.race([new Promise(r => server.on('exit', r)), sleep(30000)])
  if (!exited) server.kill()
  const bad = Object.values(result.checks).filter(c => !c.ok).length
  result.ok = bad === 0
  console.log(JSON.stringify(result, null, 1))
  process.exit(result.ok ? 0 : 1)
}
