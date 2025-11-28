#!/usr/bin/env bash
set -euo pipefail

# CONFIGURATION
CSV_FILE="/Users/stefan/sources/p-agi_lidar_2023/TimeInDaylight/ch.swisstopo.swisssurface3d-raster-LL3uCeEV.csv"
SOURCE_TIF="/Users/stefan/Downloads/ch.swisstopo.lidar_2023.dsm.tif"
OUT_DIR="/Users/stefan/Downloads/dsm_1km_tiles"

# Create output directory if it doesn't exist
mkdir -p "$OUT_DIR"

# Check that gdal_translate is available
if ! command -v gdal_translate >/dev/null 2>&1; then
  echo "Error: gdal_translate not found in PATH. Install GDAL and try again." >&2
  exit 1
fi

# Loop over each line (each URL) in the CSV
while IFS= read -r line; do
  # Skip empty lines
  [ -z "$line" ] && continue

  # Remove surrounding quotes if present
  url=${line//\"/}

  # Extract the "2597-1217" part from the URL
  # Assumes pattern ..._2023_2597-1217/...
  tile=$(echo "$url" | sed -E 's/.*_2023_([0-9]+-[0-9]+).*/\1/')

  # If we failed to extract, skip
  if [[ "$tile" == "$url" ]]; then
    echo "Warning: could not parse easting/northing from line: $line" >&2
    continue
  fi

  easting=${tile%-*}   # e.g. 2597
  northing=${tile#*-}  # e.g. 1217

  # Compute lower-left corner in meters (EPSG:2056; *1000)
  easting_m=$(( easting * 1000 ))    # 2597000
  northing_m=$(( northing * 1000 ))  # 1217000

  # 1 km tile: lower-left (E, N), so:
  # upper-left  = (E, N + 1000)
  # lower-right = (E + 1000, N)
  ulx=$easting_m
  uly=$(( northing_m + 1000 ))
  lrx=$(( easting_m + 1000 ))
  lry=$northing_m

  # Output filename
  out_tif="${OUT_DIR}/lidar_2023_${easting}-${northing}.tif"

  echo "Cutting tile ${easting}-${northing} -> ${out_tif}"
  echo "  projwin: ulx=$ulx uly=$uly lrx=$lrx lry=$lry"

  gdal_translate \
    -a_srs EPSG:2056 \
    -projwin "$ulx" "$uly" "$lrx" "$lry" \
    -projwin_srs EPSG:2056 \
    "$SOURCE_TIF" "$out_tif"

done < "$CSV_FILE"
