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
and other generators. Model files are required by ordinary mod startup; no terrain is
generated. No existing world, client configuration or installed JAR is edited. Run a
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
