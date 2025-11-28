#!/usr/bin/env bash
set -euo pipefail

########################
# CONFIG
########################

# CSV with swisssurface3d tile URLs
CSV_FILE="/Users/stefan/sources/p-agi_lidar_2023/TimeInDaylight/ch.swisstopo.swisssurface3d-raster-LL3uCeEV.csv"

# Source DSM COG (EPSG:2056)
SOURCE_TIF="/Users/stefan/Downloads/ch.swisstopo.lidar_2023.dsm.tif"

# Final output directory for 1 km TimeInDaylight tiles
FINAL_DIR="/Users/stefan/Downloads/tid_1km_tiles"

# Temporary working directory for intermediate rasters
WORK_DIR="/Users/stefan/Downloads/tid_work"

# Path to whitebox_tools binary
WBT="whitebox_tools"

# TimeInDaylight parameters
AZ_FRACTION=1.0
MAX_DIST=2000       # metres; used only in TimeInDaylight
LAT=47.3110
LON=7.6543

# Tile/core size (1 km)
TILE_SIZE=1000       # metres

# BUFFER around the 1 km tile when cutting from the COG (in metres)
BUFFER=100.0         # change as you like

########################
# SETUP
########################

mkdir -p "$FINAL_DIR" "$WORK_DIR"

# Quick checks
if ! command -v gdal_translate >/dev/null 2>&1; then
  echo "Error: gdal_translate not found in PATH. Install GDAL and try again." >&2
  exit 1
fi

if ! command -v "$WBT" >/dev/null 2>&1 && [ ! -x "$WBT" ]; then
  echo "Error: whitebox_tools not found or not executable at: $WBT" >&2
  exit 1
fi

########################
# MAIN LOOP OVER CSV
########################

while IFS= read -r line; do
  # Skip empty lines
  [ -z "$line" ] && continue

  # Remove surrounding quotes if present
  url=${line//\"/}

  # Extract the "2597-1217" part from the URL
  # Assumes pattern ..._2023_2597-1217/...
  tile=$(echo "$url" | sed -E 's/.*_2023_([0-9]+-[0-9]+).*/\1/')

  # If extraction fails, skip
  if [[ "$tile" == "$url" ]]; then
    echo "Warning: could not parse easting/northing from line: $line" >&2
    continue
  fi

  easting=${tile%-*}   # e.g. 2597
  northing=${tile#*-}  # e.g. 1217

  echo "=== Tile ${easting}-${northing} ==="

  ########################
  # CORE 1 KM EXTENT
  ########################

  # Lower-left corner (EPSG:2056)
  core_llx=$(( easting * 1000 ))     # 2597 -> 2 597 000
  core_lly=$(( northing * 1000 ))    # 1217 -> 1 217 000

  # Upper-right corner
  core_urx=$(( core_llx + TILE_SIZE ))  # +1000 m
  core_ury=$(( core_lly + TILE_SIZE ))  # +1000 m

  ########################
  # EXPANDED EXTENT (BUFFER AROUND TILE)
  ########################

  buffer_int=${BUFFER%.*}  # convert e.g. 150.0 -> 150

  exp_llx=$(( core_llx - buffer_int ))
  exp_lly=$(( core_lly - buffer_int ))
  exp_urx=$(( core_urx + buffer_int ))
  exp_ury=$(( core_ury + buffer_int ))

  base="tid_2023_${easting}-${northing}"

  dsm_exp_tif="${WORK_DIR}/${base}_dsm_exp.tif"
  tid_exp_tif="${WORK_DIR}/${base}_tid_exp.tif"
  final_tif="${FINAL_DIR}/${base}.tif"

  echo "  Core extent (1 km):   $core_llx,$core_lly to $core_urx,$core_ury"
  echo "  Expanded extent:      $exp_llx,$exp_lly to $exp_urx,$exp_ury (buffer=${buffer_int} m)"

  ########################
  # 1) CUT EXPANDED DSM FROM COG
  ########################

  echo "  -> gdal_translate DSM (expanded)"
  gdal_translate \
    -a_srs EPSG:2056 \
    -projwin "$exp_llx" "$exp_ury" "$exp_urx" "$exp_lly" \
    -projwin_srs EPSG:2056 \
    "$SOURCE_TIF" "$dsm_exp_tif"

  ########################
  # 2) RUN TimeInDaylight ON EXPANDED DSM
  ########################

  echo "  -> whitebox_tools TimeInDaylight"
  "$WBT" \
    -r=TimeInDaylight \
    -v \
    -i="$dsm_exp_tif" \
    -o="$tid_exp_tif" \
    --az_fraction="$AZ_FRACTION" \
    --max_dist="$MAX_DIST" \
    --lat="$LAT" \
    --long="$LON" \
    --start_time="sunrise" \
    --end_time="sunset"

  ########################
  # 3) CUT FINAL 1 KM TILE FROM TimeInDaylight RESULT
  ########################

  echo "  -> gdal_translate final 1 km TimeInDaylight tile"
  gdal_translate \
    -a_srs EPSG:2056 \
    -projwin "$core_llx" "$core_ury" "$core_urx" "$core_lly" \
    -projwin_srs EPSG:2056 \
    "$tid_exp_tif" "$final_tif"

  ########################
  # 4) CLEAN UP INTERMEDIATE FILES (OPTIONAL)
  ########################

  rm -f "$dsm_exp_tif" "$tid_exp_tif"

  echo "  -> Done: $final_tif"
  echo

done < "$CSV_FILE"

echo "All tiles processed. Final 1 km TimeInDaylight tiles are in: $FINAL_DIR"
