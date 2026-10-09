# 3D Engine

Drop a `.glb`, `.gltf` or `.obj` file into `plugins/3DEngine/models/`, run
`/3d reload`, and it is in the game: real meshes with their own textures and
animations, in vanilla Minecraft with no mods. Furniture, statues, vehicles,
props, creatures: anything you can download from Sketchfab or export from
Blender.

The engine bakes each file into item models drawn by obj³'s core shaders,
builds a resource pack and serves it to players itself. Bakes are cached, so a
restart only bakes what changed.

**Needs** Paper on Minecraft 26.3 (pack format 97.1). It uses Paper's mob goal
API and obj³'s 26.3 shaders.

This directory is three Gradle projects:

| Project | Plugin | What it is |
|---|---|---|
| `engine` | `3DEngine` | The engine: baking, the pack, `/3d`, the API |
| `pets` | `3DPets` | A Labrador that follows you and plays fetch: a worked example of the API |
| `flamethrower` | `3DFlamethrower` | Parked. Not to be released |

Build with `./gradlew build --offline` (JDK 17 or 21 to run Gradle).

## Commands

All need `3dengine.admin` (op by default). Look at a placed model to act on it.

| Command | Does |
|---|---|
| `/3d list` | Every model, its height and states, and any that failed to bake |
| `/3d place <model> [scale]` | Places a model on the block you look at, facing you. Saved with the world |
| `/3d give <model> [player]` | An item that places the model where it is used |
| `/3d remove` / `scale <n>` / `rotate <deg>` / `state <state>` | Changes the model you look at |
| `/3d sit [seat]` | Sits you in the model you look at |
| `/3d info [model]` | Size, states, points, seats, hitboxes, and what it costs the client |
| `/3d effect <name> [size]` | Puts down a shader effect from `effects/` |
| `/3d reload` | Re-bakes the folder and re-sends the pack |
| `/3d pack` | Re-sends you the pack |

Right-clicking a model with seats sits you in the nearest free one
(`3dengine.ride`, everyone by default). Sneak to get off.

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
  yaw: 60              # the most it turns either way, degrees
  pitch: 30
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

## Credits

obj³ (MIT, Godlander and contributors) draws the meshes; its shaders ship in the
pack and its encoder is ported in `bake/ObjCubed.java`.
