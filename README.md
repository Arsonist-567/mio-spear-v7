# Auto Spear v1.6 (Meteor addon, Minecraft 1.21.11)

Adds one module, **Auto Spear** (Combat category).

- **Flying (Auto Fly off, the default):** your own ElytraFly (Mio, Meteor...) does the flying and the speed. Auto Spear points your camera at the target and holds forward (and jump / sneak to go up or down) for you, so it still flies you in, charges, hits, pulls out and comes back by itself.
- **Flying (Auto Fly on):** the module flies you itself: at where the target is *going to be*, building speed up smoothly to Fly Speed, holds the spear charge, hits, pulls up and out just past spear reach, then swings straight back for the next one. It never stops to hover.
- **On foot:** jabs whenever the target is inside spear reach (2 to 4.5 blocks) and the cooldown is ready.

If the server pulls you back, Anti Rubberband drops the top speed a little and builds it back up.

## Build it on GitHub

1. Make a new repository and upload every file from this zip to the top level (flat is fine).
2. Open `build.yml` in the repository, press the pencil, and change its name to `.github/workflows/build.yml`. Commit.
3. Open the **Actions** tab and wait for the green tick. The jar (`auto-spear-v7-1.6.0.jar`) is under **Releases** (and in the run's Artifacts).
4. Put the jar in your `mods` folder next to Meteor Client for 1.21.11. Take the old auto-spear jar out first: both use the same module, so only one can be in at a time.

## Settings

General:

| Setting | Default | What it does |
|---|---|---|
| Sorting | Distance | Distance, Health or Angle |
| Range | 64 | How far away a target can be |
| Auto Boost | off | Fires rockets while attacking. Leave off if your ElytraFly gives the speed |
| Smart | on | Aims where the target is going and picks a part of its hitbox that isn't behind a block |
| Render | off | Box on the target and a line to the aim point |
| Color | 189, 153, 255 | Render color |
| Players / Nakeds / Animals / Hostiles / Neutrals | on / on / off / off / off | Who to attack |

Spear:

| Setting | Default | What it does |
|---|---|---|
| Mode | Auto | Auto = charge while flying or riding, jab on foot |
| Attack Style | Vertical | Vertical = climb above them and dive down on them. Straight = fly straight at them |
| Style Key | none | Bind a key: each press swaps Vertical <-> Straight mid-fight (says which in chat, and shows in the module list) |
| Height | 12.7 | Vertical: how far above the target each dive starts |
| Angle | 64.5 | Vertical: how steep the dive is (90 = straight down) |
| Auto Fly | off | Off = your ElytraFly does the flying and speed. On = this module flies you itself |
| Auto Forward | on | (Auto Fly off) Holds your forward key so your ElytraFly flies you in |
| Auto Vertical | on | (Auto Fly off) Holds jump / sneak to climb or drop toward the target |
| Fly Speed | 3.0 | Top speed in blocks per tick (3 = about 216 km/h) |
| Acceleration | 0.2 | Speed added per tick until Fly Speed. Gradual = less rubber banding |
| Fly Turn | 0.55 | How sharply the flight path bends toward them. 1 = instant, lower = smoother |
| Anti Rubberband | on | Backs off a little when the server pulls you back |
| Steer | on | Turns your camera so your ElytraFly flies you at them. Off = you steer, the spear is aimed server side |
| Turn Speed | 18 | Higher = snappier camera, lower = smoother. 0 = instant. Always locks on exactly right before a hit |
| Rehit Distance | 5.5 | How far you pull out after a hit before turning back |
| Pull Up Angle | 20 | How steeply you climb out after a hit |
| Intercept | on | Flies at where they will be instead of chasing their tail |
| Max Lead | 20 | Furthest ahead (ticks) Intercept aims |
| Ping Ticks | 1 | Extra ticks to aim ahead for ping, about 1 per 50 ms of real ping |
| Auto Switch | on | Swaps to a spear when a target is found |
| Boost Delay | 30 | Ticks between rockets |

## Using it

- **Auto Fly off (default):** turn Mio's ElytraFly **on** in control mode, open your elytra, turn Auto Spear on. Hands off: it holds the keys and steers.
- **Auto Fly on:** turn your own ElytraFly **off** (both would fight over your movement), open your elytra, turn Auto Spear on.
- Scroll off the spear to pause (you keep gliding, camera is left alone), scroll back to carry on.
- Friends from Meteor's friends list are never targeted.
