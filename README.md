# 3D Engine

Drop a `.glb`, `.gltf` or `.obj` file into `plugins/3DEngine/models/`, run
`/3d reload`, and it is in the game: real meshes with their own textures and
animations, in vanilla Minecraft with no mods. Furniture, statues, vehicles,
props, creatures: anything you can download from Sketchfab or export from
Blender.

A Sketchfab download can go in as it comes: drop the `.zip` in the models
folder and the engine unpacks it into a folder of the same name, and a file
called `scene.gltf` (every Sketchfab download's) takes its folder's name. Its
`.yml` is `scene.yml`, beside it.

The engine bakes each file into item models drawn by obj³'s core shaders,
builds a resource pack and serves it to players itself. Bakes are cached, so a
restart only bakes what changed.

## Requirements

- **Spigot or Paper on Minecraft 26.3.** The shaders and the pack format
  (97.1) are that version's. On Paper, models that walk borrow Paper's mob
  pathfinding; on Spigot they use a path search of the engine's own.
- Players need nothing installed: the pack is sent to them as they join.

## Using it with other packs

The models are new item models in their own `3dengine` namespace, but drawing
them takes obj³'s **core shaders**, and those replace vanilla's: the pack always
replaces the `block`, `entity`, `item` and `terrain` core shaders (and adds
obj³'s `objmc_*.glsl` includes beside them), and when `effects/` holds an
effect it replaces `text.vsh` and `text.fsh` too.

A core shader is one file per name, so of two packs on a client that replace
the same one, only the higher pack's is used. If another pack (another objmc
or obj³ model pack, say) is above 3D Engine's and replaces any of those four,
models stop drawing; if it is below, whatever its shader did is lost. The same
goes for the text shaders and anything that replaces them, such as RP Engine's
shader objects or UI Engine, when you use effects. Everything else in other
packs (models, textures, sounds, fonts) stacks with this one as usual.

## Installing

1. Put `3DEngine.jar` in `plugins/` and start the server.
2. Open port **8163** (or set `pack.port` in `config.yml`), and set `pack.url`
   to the address players reach it on, e.g. `http://play.example.com:8163`.
   That is where they download the pack from.
3. Put model files in `plugins/3DEngine/models/` and run `/3d reload`.

Already sending your own pack? Set `pack.send: false` and merge
`plugins/3DEngine/pack.zip` into yours.

## Commands

Each has its own permission (below), all op by default. Look at a placed model to act on it.

| Command | Does |
|---|---|
| `/3d list` | Every model, its height and states, and any that failed to bake |
| `/3d place <model> [scale]` | Places a model on the block you look at, facing you. Saved with the world |
| `/3d give <model> [player]` | An item that places the model where it is used |
| `/3d remove` / `scale <n>` / `rotate <deg>` / `state <state>` | Changes the model you look at |
| `/3d sit [seat]` | Sits you in the model you look at |
| `/3d info [model]` | Size, states, points, seats, hitboxes, and what it costs the client |
| `/3d quality [model] [triangles\|reset] [texture width]` | How much detail a model keeps: shows the settings, or sets one model's and re-bakes |
| `/3d effect <name> [size]` | Puts down a shader effect from `effects/` |
| `/3d reload` | Re-reads `config.yml`, re-bakes the folder and re-sends the pack |
| `/3d pack` | Re-sends you the pack |

Right-clicking a model with seats sits you in the nearest free one. Sneak to
get off.

Every subcommand has its own node, so you can hand out exactly the ones you
want. `3dengine.admin` grants every command and placing with a model item in
one line; the plugin never checks it itself. Somebody with none of the nodes
gets a "no permission" message, and tab-completion only offers what they may
use. Using a model item without `3dengine.place` places nothing and says which
permission it needs.

| Permission | Default | |
|---|---|---|
| `3dengine.admin` | op | All of the below except riding and update notices |
| `3dengine.list` | op | `/3d list` |
| `3dengine.place.command` | op | `/3d place` |
| `3dengine.give` | op | `/3d give`, to yourself |
| `3dengine.give.others` | op | `/3d give <model> <player>`, to somebody else (needs `3dengine.give` too) |
| `3dengine.remove` | op | `/3d remove` |
| `3dengine.scale` | op | `/3d scale` |
| `3dengine.rotate` | op | `/3d rotate` |
| `3dengine.state` | op | `/3d state` |
| `3dengine.sit` | op | `/3d sit` |
| `3dengine.info` | op | `/3d info` |
| `3dengine.quality` | op | `/3d quality` |
| `3dengine.effect` | op | `/3d effect`, including `/3d effect clear` |
| `3dengine.reload` | op | `/3d reload` |
| `3dengine.pack` | op | `/3d pack` |
| `3dengine.place` | op | Placing models with a model item (from `/3d give`) |
| `3dengine.ride` | everyone | Sitting in a model's seats by right-clicking it |
| `3dengine.update` | op | Told when a newer version is out |

`/3d place` has `3dengine.place.command` rather than `3dengine.place` because
`3dengine.place` already meant using a model item: giving players the items
does not also let them put down any model they like.

## Configuration

`plugins/3DEngine/config.yml` has the pack server (`pack.*`), the default and
largest detail a model keeps (`quality.*`, see below), and a height offset for
seated riders. Edit it and run `/3d reload` and it takes effect, except for
`pack.host`, `pack.port` and `pack.bind`, which the pack server only reads when
the server starts (the reload says so when one of them has changed). Two
switches concern what the plugin connects to:

- **`update-check: true`** asks SpigotMC every six hours whether a newer 3D
  Engine is out, and if so tells the console and anybody with `3dengine.update`.
  Set it to `false` to turn that off.
- **`telemetry: true`** is an anonymous usage count: every 30 seconds a random
  id the plugin made up the first time it started, how many players are online,
  and the names of installed plugins that depend on 3D Engine go to
  resourcepack.ai. No address, server name, player names or content. Set it to
  `false` to turn it off.

## The model's .yml

Every file can have a `.yml` of the same name beside it. Every key is optional:
a bare file becomes one looping state per animation clip, at its own size.

Positions you give **in blocks** (seats, hitboxes) are measured from the middle
of the model's base: `x` to its right, `y` up, `z` forward.

```yaml
# Size and placement
height: 1.8            # resize to this many blocks tall (or scale: blocks per file unit)
yaw: 90                # turn it so its front faces the way it is placed
offset: [0, 0, 0]
triangles: 4000        # simplify to about this many
texture-width: 256
hide: [Ground, Camera] # leave out parts of the file (a ground plane, a backdrop), by node name

# Animation (glTF clips)
default-state: idle
states:
  idle: {clip: Idle, fps: 10, syncs: 2}
  walk: {clip: Walk, fps: 20, wait: true}
  sit:  {clip: Idle, pose: {Body: {rotate: [-40, 0, 0], translate: [0, -0.3, 0]}}}
transitions:
  - idle <-> walk
  - {from: idle, to: sit, frames: 12}
points:                # named places that follow a bone (a mouth, a hand)
  mouth: {bone: Head, at: [0, 2.1, 2.4]}

# Looking at players
look:
  bone: Head           # only the head turns, on its neck; leave out to turn the whole model
  with: [Jaw]          # more bones that turn with it (see below)
  yaw: 70              # the most it turns either way, degrees
  pitch: 35
  range: 8             # blocks
  speed: 9             # degrees per tick
  states: [idle, sit]  # states it looks in (default: all)

# Sitting and riding
seats:
  driver: {at: [-0.4, 0.5, 0.2]}
  passenger: {at: [0.4, 0.5, 0.2], bone: Body, pose: standing}
drive:                 # the driver steers: it goes where they look while they hold forward
  seat: driver         # default: the first seat
  speed: 0.2           # blocks per tick; sprint: 0.32, back: 0.08
  turn: 7              # degrees per tick
  jump: 0.42           # 0 = it can't jump
  brain: horse         # the invisible mob that carries it; picked by size if left out

# Clicking and hitting
hitbox: false          # not clickable at all
hitboxes:              # boxes of your own instead of the fitted ones
  - {at: [0, 0, 0.6], width: 1, height: 1.2}
solid: true            # placed models fill their boxes with barriers
```

**Detail.** `triangles` and `texture-width` default to the server's
`quality.triangles` and `quality.texture-width` in `config.yml` (20,000 and
512), and nothing keeps more than `quality.max-triangles` and
`quality.max-texture-width`. `/3d quality <model> <triangles> [width]` sets one
model's in game, over what its `.yml` says, and is kept in `config.yml`, so a
plugin that re-copies its model files cannot undo it. It reads `config.yml`
from disk before saving, so edits you have made to the file by hand survive. An animated model stores
its whole mesh once per frame and its texture once per animation, so the
texture is usually what makes a pack big.

Also: `animations-from` (drive this model with another file's clips, matched by
bone name), `retarget`, `rebind`, `legs` (IK chains that keep feet planted),
`blend`, `sync-at`, `icon-scale`, `item` (hold it as an item), `locomotion`
(state names and speeds for a walking model). Any other key is kept for
plugins to read with `Model.settings()`.

**Hitboxes** are fitted to the model unless you write your own: one box, or a
row of up to six along a long model, so a car can be clicked at either end.

**A seat** is where the rider's hips go (`pose: standing`: their feet). With a
`bone` it rides that bone through every animation. A rider sees through the
model's own hitboxes, so they can still click past it. If riders sit a little
high or low on your server, `seats.height-offset` in `config.yml` moves them all.

**Solid** barriers are only put in empty blocks, never where a seat's rider
goes, and are taken away when the model is removed, moved or resized. Models
that move (driven, or walked by a plugin) are never solid.

**Looking with a bone** draws the head as a second model on its own display,
turned on its neck. It costs one more item model per animation and an icon.
Rigs often hang the jaw, tongue or ears beside the head bone rather than under
it; any bone beside it whose vertices sit within the head's own space turns
with the head too (the bake log says which), and `with:` adds any it misses.

## The API

Depend on `3DEngine` and call `Engine3D.get()`.

```java
Engine3D engine = Engine3D.get();
engine.whenReady(() -> {
    ModelInstance chair = engine.place("furniture/armchair", location); // saved with the chunk
    chair.mount(player, null);              // into the nearest free seat
    chair.lookAt(player);                   // or lookAround(), lookAway()

    ModelInstance dog = engine.spawn("dog", location);                  // not saved
    Mover legs = engine.mover(dog, EntityType.WOLF);
    legs.follow(player);
});
```

`ModelInstance` changes state (through the model's transitions), says where a
point is on any frame, scales, seats riders, looks, and turns its hitboxes on
and off. `Engine3D.instance(entity)` finds the model any of its entities
belongs to: its display, head, a hitbox, a seat or its brain.

### Events

All in `ai.resourcepack.engine3d.api.event`:

| Event | When | Cancel to |
|---|---|---|
| `ModelInteractEvent` | A player right-clicks a model | Stop the default (sitting down) |
| `ModelDamageEvent` | A player punches it, or a projectile or mob hits it | Tell later listeners |
| `ModelMountEvent` | Something is about to sit in a seat | Keep them off |
| `ModelDismountEvent` | A rider is getting off | Keep them on, when `isCancellable()` |
| `ModelStateChangeEvent` | A new state's loop starts | |
| `ModelSpawnEvent` / `ModelRemoveEvent` | A model is put down / taken away | |

A model has no health of its own: what a hit does is up to you.

## Addons

An addon is a plugin with `depend: [3DEngine]` that calls the API above and
compiles against the engine's jar. [3D Pets](https://github.com/resourcepack-ai/3d-pets),
a Labrador that follows you and plays fetch, is one, and its `build.gradle`
shows how to compile against a released engine.

## Building

```
./gradlew build        # engine/build/libs/3DEngine-<version>.jar
```

Gradle 8.10.2 needs a JDK it can run on: 17 or 21 work, 24 does not. If your
default `java` is newer, `JAVA_HOME=/path/to/jdk-21 ./gradlew build`. The jar
targets Java 21 through a toolchain Gradle downloads on its own.

`3DEngine-<version>.jar` is the plugin. `3DEngine-<version>-plain.jar` is the
same classes without the shaded library, for addons to compile against.

`ai.resourcepack:common`, a small shared library (the update check and the
usage count), comes from `https://repo.resourcepack.ai`, and is shaded into the
jar and relocated.

To try a model without a server, `./gradlew :engine:test --tests '*SampleBakeTest*' -Dsample=path/to/model.glb`
bakes it (with its `.yml`, if it has one) and renders previews into
`engine/build/previews/`.

## Support

Questions, bugs and suggestions: [resourcepack.ai/discord](https://resourcepack.ai/discord),
or an issue on this repository.

## Licence

GPL-3.0-or-later. See [`LICENSE`](LICENSE).

## Credits

[obj³](https://github.com/JagerMeistars/obj-cubed), after Godlander's
[objmc](https://github.com/Godlander/objmc) (MIT, Godlander and contributors),
draws the meshes: its core shaders ship in the pack (with their licence, in
`engine/src/main/resources/pack/`) and its encoder is ported in
`bake/ObjCubed.java`.
