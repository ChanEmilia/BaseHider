## Paper 1.21 (only tested on 1.21.8)
~~Side project I only spent a week on, don't expect this build to be perfect~~ Actually pretty good now\
Inspired by the DonutSMP antiFreeCam plugin\
This plugin implements an antiESP/FreeCam mechanic by rendering all underground blocks as solid blocks, ie even if a player builds a base at bedrock, players are only shown deepslate until the player is within a specific proximity.
## Rundown:
  - Uses PacketEvents to intercept and manipulate packets. Changes are purely visual and do not alter actual server world data
  - Blocks and entities below a configured y level are hidden unless the player is within a specific volumetric distance
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
# ======================================================
#                BaseHider Configuration
# ======================================================

# Global Performance Settings
# These settings affect how hard the plugin works to update chunks
performance:
  # How many chunk sections can be updated per server tick
  # Higher = Faster updates but potentially more lag
  # Lower = Smoother server performance but "pop-in" effect might be visible
  # Default: 200 (approx 25 updates per tick)
  max-updates-per-second: 200

  # How often (in ticks) to re-check all players' surroundings
  # 20 ticks = 1.0 seconds. Lower values check more often but use more CPU
  # Default: 20
  rescan-interval: 20

  # If true, chunks that were revealed will be hidden again when the player walks away
  # Set to 'false' for better performance (chunks stay revealed until relog)
  # Default: false
  rehide-chunks: false

# ======================================================
#                   World Settings
# ======================================================
# You can add as many worlds as you want here
# The key must match the folder name of your world (e.g., "world", "world_nether")

world:
  # Enable the hider for this world?
  enabled: true

  # Also hide entities inside hidden chunks?
  hide-entities: true

  # The fake block to show instead of real blocks
  # Set to most abundant block in the hidden region
  replacement-block: "DEEPSLATE"

  # The Y-level BELOW which blocks will be hidden.
  # Example: If set to 0, everything below Y=0 is hidden.
  # Default: -1
  block-hide-y: -1

  # The radius (in blocks) around the player where real blocks are shown
  # Everything outside this radius (and below block-hide-y) will be fake
  # Keep this on the low side to make finding bases harder
  # Default: 48
  show-distance: 48

  # If a player is standing ABOVE this Y-level, the hiding system is active
  # If they go below this level (e.g. into a cave), the hiding disables so they can see
  # This prevents players from constantly seeing fake blocks when underground
  # Default: 25
  show-y: 25

# Example for other dimensions
world_nether:
  enabled: false
  hide-entities: true
  replacement-block: "NETHERRACK"
  block-hide-y: 31
  show-distance: 32
  show-y: 60
```
