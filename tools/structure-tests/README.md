# Structure placement checks

`./gradlew :common:test -PuseCpu=true` exercises Minecraft's actual placement codecs and
candidate generator across scales 1–6, five seeds, negative coordinates, reverse query
order, independent generator snapshots, placement-field preservation and codec limits.
It does not run terrain inference or assert a final village count after biome rejection.

For the transformed NeoForge integration, use Java 21, the existing model files, Terralith
2.6.2 and Lithostitched 1.8.1 (the startup model optimizer needs a 10 GiB heap):

```sh
python tools/structure-tests/run.py --java-home /path/to/jdk-21 \
  --models /path/to/terrain-diffusion-models \
  --terralith /path/to/Terralith.jar --lithostitched /path/to/lithostitched.jar
```

The explicit init script adds test-only instrumentation to the development server.
Two owned directories under ignored `build/structure-tests/` check normal scale-4
initialization and the configuration opt-out. Each process exits inside the world-load
event, before spawn generation, and writes `structure-checks.txt`. Checks cover real
datapack tags, the production lifecycle, an already-initialized locate index, exclusion
queries against the scaled grid, idempotence and unchanged shared registry definitions
and other generators. Model files are required by ordinary mod startup; the default
checks do not generate terrain. No existing world, client configuration or installed JAR is edited. Run a
normal build without the init script afterward to produce distribution JARs.

The checks establish candidate placement behavior. They do not establish exact vanilla
village density after biome/slope rejection, terrain suitability, Fabric runtime behavior,
or uninterrupted village boundaries where an existing world changes placement policy.

## Before/after density maps

To export native candidates for scales 1–6 alongside the coarse model backdrop, add
`--maps` to the `run.py` command above. The export is `density-data.json` in the scaled
disposable server directory. The init script also accepts `-PstructureTestMaps=true`.
It runs the coarse inference model, but does not generate Minecraft chunks or villages.
Both `minecraft:villages` and `terralith:rare_village` use Minecraft's actual candidate
placement, frequency and exclusion checks. Before/after use the same seed and boundaries.

```sh
python tools/structure-tests/density_maps.py \
  --data build/structure-tests/scaled/density-data.json \
  --output build/village-density-review
```

Requires Pillow. The background reuses the existing seed-atlas web-map palette and hill
shading in `map_background.py`; it shows coarse elevation/climate, not actual surface blocks.
The renderer produces six before/after pairs for the same scaled terrain region, six for
a fixed 8,192-block square, two overview sheets and a receipt with counts and source hashes.
The scale-1 candidate lists must match exactly. All markers, including ocean positions,
are explicitly **candidates before biome and terrain rejection**, not generated villages.
This separates the grid policy's density effect from biome distribution and terrain
suitability. Exact finite-window counts can differ from the inverse-square grid-cell rate
because of boundaries and unchanged exclusion radii.

### Valid village starts in a land-focused region

Add `--valid-villages` to the fixture runner to generate the replacement, player-relevant
comparison. It chooses a predominantly land, mild, low-elevation coarse-model window
without consulting any village result, then keeps that same native terrain region across
scales 1–6. This executes the real `ChunkGenerator.createStructures` path in detached
`ProtoChunk`s: placement/frequency/exclusions, weighted structure selection and retries,
biome validation, real terrain-height sampling, full jigsaw piece generation and the
fork's unsupported-start veto. Only valid `StructureStart`s are exported. Markers use
the accepted layout bounding-box center; the export retains the actual structure ID and
piece count. No substitute biome classifier or approximate land-mask rejection is used.

The result is `valid-village-data.json`; render it with the same `density_maps.py` command
to a separate output directory. The overview and all six individual pairs show accepted
village starts, not the earlier candidate grid. The backdrop is still coarse geography;
building blocks and later decoration are not populated. No player save is used. Real
terrain inference makes this slower than the candidate-only check. Each unique start is
evaluated once across before/after, and checkpointed in the owned fixture directory.

`--village-region centerX,centerZ,extent` selects an explicit native-coordinate window;
record why it was selected and preserve it across the comparison. `--coarse-end` extends
the backdrop when needed. The exporter rejects windows outside its backdrop or with
less than 80% coarse land. `--backend windows` uses DirectML; an optional
`--directml-agent` can preload an existing instance-local DLL. Both sides always use the
same backend. The backend and region-selection method are recorded in the export.
