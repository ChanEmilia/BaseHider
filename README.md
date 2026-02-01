## Requires [ProtocolLib](https://www.spigotmc.org/resources/protocollib.1997/), for 1.21
Side project I only spent a week on, don't expect this build to be perfect\
Inspired by the DonutSMP antiFreeCam plugin\
This plugin implements an antiESP/FreeCam mechanic by rendering all underground blocks as solid blocks, ie even if a player builds a base at bedrock, players are only shown deepslate until the player is within a specific proximity.
## Rundown:
  - Uses ProtocolLib to intercept and manipulate packets. Changes are purely visual and do not alter actual server world data
  - Blocks below a configured y level are hidden unless the player is within a specific volumetric distance
  - Instant chunk hiding so loading in loads of new chunks won't be too costly
  - Multi world configuration!
## Optimisations & Architecture:
  - Distance calculations, packet generation, and state tracking occur asynchronously
  - O(1) solid generation for chunk sections
  - Packet throttling to prevent network saturation
  - Uses priority queueing to sort updates by distance, for a more seamless user experience
  - Packet caching to reduce CPU load when players revisit areas
## Configuration:
```yaml
# Define settings per world
# You can add custom dimension names here (e.g., "spawn", "resource_world")!

# Global Performance Settings
performance:
  updates-per-second: 100  # Total chunklets to update per second
  rescan-interval: 10      # Force rescan every 10 ticks
  # If false, chunks that have been revealed will stay revealed until the player quits
  # Saves performance and I guess if they've already seen it there's no reason to rehide
  # If true, chunks will turn back into replacement-block when the player moves away
  rehide-chunks: false

world:
  enabled: true
  # Whether or not to hide entities as well, disable if your simulation
  # distance is low enough that entities hide automatically anyway
  # Or if you have a raytraced anti ESP plugin
  hide-entities: true
  # Fake block used to mask real blocks
  replacement-block: DEEPSLATE
  # Hide all blocks at or below this y level, non-inclusive
  block-hide-y: 0
  # Radius around the player where the real blocks are revealed
  show-distance: 64
  # Reveal all blocks when player is below this y level
  # Useful for when players enter large caves or underground megabases
  show-y: 16
```
