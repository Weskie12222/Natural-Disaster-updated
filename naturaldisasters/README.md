# Natural Disasters (Fabric, Minecraft 26.2)  -  v1.1.0

Earthquakes, tornadoes, tsunamis, floods, hurricanes and ground rifts that open into caves,
plus sirens and detector tools that warn you before they hit. Disasters behave differently in
every environment, villages react, and the tools are *required* to see them coming.

## Build

Requirements: **JDK 25**, internet access for Gradle (Fabric Maven).

```
./gradlew build          # jar ends up in build/libs/naturaldisasters-1.1.0.jar
./gradlew runServer      # or runClient, to test
```

Toolchain: Minecraft 26.2 (unobfuscated, official Mojang names), Fabric Loader 0.19.3,
Loom 1.17, Gradle 9.5.1, Fabric API 0.161.0+26.2.

## Warning siren

- Uses your recording (80 s, cut into 16 x 5 s mono pieces and played back to back, so it wails continuously).
- **Audible from 300 blocks** (volume fades with distance; the server sends it up to 320).
- Wails automatically when a Strong/Extreme disaster (Moderate+ for tsunamis) is forecast or active in range, or on a redstone signal.
- Takes up to 5 seconds to start (it starts on the next 5 s boundary).
- Villagers within 96 blocks take cover while it wails.
- Players within earshot of a wailing siren receive the forecast messages.

## Detectors (needed to be warned)

By default you are **only warned if you carry the right detector or are within earshot of a wailing siren**.
Without them you only get natural cues (rumbling, rain starting, a distant roar). Change with `/disaster warnings everyone`.

| Item | Detects | Range |
|---|---|---|
| Seismograph | earthquakes, ground rifts | 400 |
| Tsunami Detector | tsunamis | 600 |
| Storm Radar | tornadoes, hurricanes | 512 |
| Flood Gauge | floods | 320 |

Carry one anywhere in your inventory for action-bar alerts; right-click for a full readout.

## Disasters and how they change with the terrain

| | Plains / forest | Desert, badlands | Snow | Jungle, swamp | Mountains | Ocean / coast |
|---|---|---|---|---|---|---|
| **Earthquake** | shaking, collapses | sinkholes in sand | avalanches on snowy slopes | sinkholes in mud | landslides on slopes | tsunami follows (strong+) |
| **Tornado** | tornado: dizzy | Dust Devil: blind + slow, faster | Snow Vortex: freezes | Jungle Twister | Mountain Whirlwind | Waterspout |
| **Hurricane** | rain, lightning, flash floods | Sandstorm: blind + slow, no rain | Blizzard: freezing unless near light | Tropical Cyclone: floods | landslides from rain | storm surge (tsunami) at landfall |
| **Flood** | normal | Flash Flood: fast, strong current | Meltwater: freezing water | Monsoon: bigger, long | Flash Flood | - |
| **Tsunami** | - | - | Ice Tsunami: ice floes freeze you | - | - | warm coast: 25% taller |
| **Ground rift** | digs into caves | edges crumble | - | - | extreme = Magma Rift (lava) | - |

### Earthquake in detail
- Foreshocks: rumbles and small tremors before the main shock.
- Main shock builds up, peaks, fades. Strongest near the epicenter. Slowness and nausea for players.
- Roofs and ledges collapse as falling blocks; **caves and mines cave in on you**.
- Sinkholes in sand/gravel/mud, landslides on steep ground, avalanches on snow, surface cracks.
- Strong+: fires start near torches, lanterns and campfires; a ground rift may open; coastal quakes trigger a tsunami.
- **Aftershocks** follow (weaker, 15-60 s later).

## Challenge

- Warnings need detectors or sirens. Stronger disasters give less lead time.
- World difficulty scales damage (peaceful x0.4, easy x0.7, hard x1.4), natural severity and frequency. No natural disasters on peaceful.
- Chains: quake -> aftershocks + rift + fires + tsunami; tsunami -> coastal flood; hurricane -> floods, tornadoes, landslides, storm surge.
- Shelter: being underground with a roof protects you from tornadoes and wind. Cave-ins hurt underground during quakes.
- Natural disasters go after villages 40% of the time.

## Villages

- Villagers hide in their homes (vanilla bell behaviour) when a disaster is forecast nearby or a siren wails.
- Village bells toll during the warning and the disaster.
- Villagers caught in the open when it hits panic.

## Commands (game-master permission)

```
/disaster start <earthquake|fissure|tornado|hurricane|tsunami|flood> [minor|moderate|strong|extreme]
/disaster stop
/disaster list
/disaster env                      # shows the environment type where you stand
/disaster natural <true|false>
/disaster frequency <minutes>      # average time between natural disasters (default 15)
/disaster warnings <detectors|everyone>
/disaster siren [seconds] [radius] # test all sirens near you
```

Testing tip: `/disaster start` with default warnings only tells *you* the disaster started. To see the forecast,
hold the matching detector, stand within 300 blocks of a siren, or run `/disaster warnings everyone`.

## Notes and limits

- Disaster state is kept in memory. Temporary water is removed when the server stops cleanly.
- Tornado/hurricane particles render within ~32 blocks. Sounds and warnings carry further.
- Rumble/roar/wave are redirects to vanilla sounds; the siren is your recording (assets/naturaldisasters/sounds/).
- Tuning knobs: top of each disaster class, `DisasterManager` (`meanSeconds`, weights per environment).
