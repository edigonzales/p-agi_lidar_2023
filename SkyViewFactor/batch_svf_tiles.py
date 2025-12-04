#!/usr/bin/env python
"""
Batch-SVF-Berechnung für 1km-Kacheln aus einem großen COG-DEM.

Ablauf:
- CSV einlesen, pro Zeile eine Tile-Id wie "2595-1223" aus der einzigen Spalte extrahieren
- daraus unteren linken Eckpunkt (x,y) in LV95 (EPSG:2056) ableiten:
    x = 2595 * 1000, y = 1223 * 1000
- 1km-Kachel mit 100m Buffer aus großem DEM schneiden
- darauf Sky View Factor berechnen (8-bit)
- aus dem gebufferten SVF exakt die 1km-Kachel (ohne Buffer) mittels Bounding Box ausschneiden
- 1km-SVF-Kachel ins Output-Verzeichnis schreiben

Benötigte Pakete:
    pip install rasterio rvt-py numpy

Beispielaufruf:
    python batch_svf_tiles.py \
        --dem /Users/stefan/Downloads/ch.swisstopo.lidar_2023.dsm.tif \
        --csv /Users/stefan/sources/p-agi_lidar_2023/TimeInDaylight/ch.swisstopo.swisssurface3d-raster-LL3uCeEV.csv \
        --out-dir /Users/stefan/Downloads/sv_1km_tiles
"""

import argparse
import csv
import logging
import os
import re
import tempfile

import numpy as np
import rasterio
from rasterio.windows import from_bounds, transform as window_transform, Window

import rvt.vis
import rvt.default


# -------------------------
# SVF-Berechnung (8-bit)
# -------------------------

def compute_svf_8bit(in_dem_path: str, out_svf_path: str,
                     svf_n_dir: int = 16,
                     svf_r_max: int = 10,
                     svf_noise: int = 0,
                     ve_factor: float = 1.0) -> None:
    """
    Berechnet den Sky View Factor (SVF) aus einem DEM-GeoTIFF
    und speichert ihn als 8-bit-GeoTIFF.

    - Input:  DEM mit Geo-Referenz
    - Output: SVF 0–255 (uint8), NoData = 0
    """
    dem_dict = rvt.default.get_raster_arr(in_dem_path)
    dem_arr = dem_dict["array"]
    dem_res_x, dem_res_y = dem_dict["resolution"]
    dem_no_data = dem_dict["no_data"]

    svf_dict = rvt.vis.sky_view_factor(
        dem=dem_arr,
        resolution=dem_res_x,
        compute_svf=True,
        compute_asvf=False,
        compute_opns=False,
        svf_n_dir=svf_n_dir,
        svf_r_max=svf_r_max,
        svf_noise=svf_noise,
        ve_factor=ve_factor,
        no_data=dem_no_data,
    )

    svf_arr = svf_dict["svf"].astype(np.float32)

    # NoData-Maske (rvt setzt üblicherweise NaN)
    nodata_mask = np.isnan(svf_arr)

    # Normale Werte begrenzen und auf 0–255 skalieren
    svf_arr = np.clip(svf_arr, 0.0, 1.0)
    svf_8bit = (svf_arr * 255.0).round().astype(np.uint8)

    svf_nodata_8bit = 0
    svf_8bit[nodata_mask] = svf_nodata_8bit

    # Als 8-bit-GeoTIFF speichern
    rvt.default.save_raster(
        src_raster_path=in_dem_path,
        out_raster_path=out_svf_path,
        out_raster_arr=svf_8bit,
        no_data=svf_nodata_8bit,
        e_type=1,  # Byte
    )


# -------------------------
# Hilfsfunktionen
# -------------------------

TILE_PATTERN = re.compile(r"(\d{4}-\d{4})")


def extract_tile_id_from_cell(cell: str):
    """
    Sucht in einem String (z.B. URL oder Pfad) nach einem Muster 0000-0000.
    Gibt z.B. "2595-1223" zurück oder None.
    """
    m = TILE_PATTERN.search(cell)
    if m:
        return m.group(1)
    return None


def tile_id_to_coords(tile_id: str):
    """
    "2595-1223" -> (x_min, y_min) = (2595000, 1223000)
    """
    x_str, y_str = tile_id.split("-")
    x = int(x_str) * 1000
    y = int(y_str) * 1000
    return x, y


def ensure_out_dir(path: str):
    if not os.path.isdir(path):
        os.makedirs(path, exist_ok=True)


# -------------------------
# Hauptlogik
# -------------------------

def process_tiles(dem_path: str,
                  csv_path: str,
                  out_dir: str,
                  buffer_m: float = 100.0,
                  tile_size_m: float = 1000.0,
                  svf_n_dir: int = 16,
                  svf_r_max: int = 10,
                  svf_noise: int = 0,
                  ve_factor: float = 1.0):
    """
    Liest die 1-Spalten-CSV, schneidet je Zeile eine 1km-Kachel mit Buffer aus,
    berechnet SVF und schreibt eine 1km-SVF-Kachel ohne Buffer ins out_dir.

    Randkacheln:
    - Wenn der Buffer ins Leere geht (außerhalb des DEMs), wird das Fenster
      beim Zuschneiden an das Dataset geclippt.
    - Der innere Ausschnitt (1km) wird per Bounding Box über from_bounds
      aus dem SVF extrahiert.
    """
    ensure_out_dir(out_dir)

    logging.info(f"Öffne DEM: {dem_path}")
    with rasterio.open(dem_path) as src:
        transform = src.transform
        crs = src.crs
        dem_nodata = src.nodata

        # Pixelgröße aus Transform (nur zur Info / Debug)
        pixel_size_x = transform.a
        pixel_size_y = -transform.e  # e ist normalerweise negativ

        logging.info(f"Pixelgröße: {pixel_size_x} x {pixel_size_y} (m)")

        full_dem_window = Window(0, 0, src.width, src.height)

        # CSV Zeile für Zeile durchgehen (eine Spalte pro Zeile)
        with open(csv_path, newline="", encoding="utf-8") as f:
            reader = csv.reader(f)

            for idx, row in enumerate(reader):
                # Leere Zeilen überspringen
                if not row:
                    continue

                cell = row[0]
                tile_id = extract_tile_id_from_cell(cell)

                if tile_id is None:
                    logging.warning(f"Zeile {idx+1}: keine Tile-ID gefunden, überspringe.")
                    continue

                try:
                    x_min_tile, y_min_tile = tile_id_to_coords(tile_id)
                except Exception as e:
                    logging.warning(f"Zeile {idx+1}: ungültige Tile-ID '{tile_id}': {e}")
                    continue

                # Bounding Box der 1km-Kachel (ohne Buffer)
                x_min_core = x_min_tile
                y_min_core = y_min_tile
                x_max_core = x_min_tile + tile_size_m
                y_max_core = y_min_tile + tile_size_m

                # Bounding Box der Kachel mit Buffer (100m) in Projektionskoordinaten
                x_min = x_min_core - buffer_m
                y_min = y_min_core - buffer_m
                x_max = x_max_core + buffer_m
                y_max = y_max_core + buffer_m

                logging.info(
                    f"Tile {tile_id}: bbox core:     ({x_min_core}, {y_min_core}) - ({x_max_core}, {y_max_core})"
                )
                logging.info(
                    f"Tile {tile_id}: bbox mit Buffer: ({x_min}, {y_min}) - ({x_max}, {y_max})"
                )

                # Fenster für die gebufferte Bounding Box
                window_buf: Window = from_bounds(x_min, y_min, x_max, y_max, transform=transform)
                window_buf = window_buf.round_offsets().round_lengths()

                # *** WICHTIGER FIX: an Dataset-Fenster clippen, damit Offsets nicht negativ werden ***
                window_buf = window_buf.intersection(full_dem_window)

                if window_buf.width <= 0 or window_buf.height <= 0:
                    logging.warning(
                        f"Tile {tile_id}: gebuffertes Fenster liegt komplett außerhalb des DEMs, überspringe."
                    )
                    continue

                # DEM-Ausschnitt lesen
                dem_buf = src.read(1, window=window_buf)
                transform_buf = window_transform(window_buf, transform)

                # Temporäre DEM-Datei für diese Kachel (mit Buffer)
                with tempfile.TemporaryDirectory() as tmpdir:
                    tmp_dem_path = os.path.join(tmpdir, f"dem_{tile_id}_buf.tif")
                    tmp_svf_buf_path = os.path.join(tmpdir, f"svf_{tile_id}_buf.tif")

                    # Gebuffertes DEM schreiben
                    profile = src.profile.copy()
                    profile.update(
                        {
                            "height": dem_buf.shape[0],
                            "width": dem_buf.shape[1],
                            "transform": transform_buf,
                            "count": 1,
                            "dtype": dem_buf.dtype,
                            "nodata": dem_nodata,
                            "compress": "LZW",
                        }
                    )

                    with rasterio.open(tmp_dem_path, "w", **profile) as dst_dem:
                        dst_dem.write(dem_buf, 1)

                    # SVF (8-bit) für diese gebufferte Kachel berechnen
                    compute_svf_8bit(
                        in_dem_path=tmp_dem_path,
                        out_svf_path=tmp_svf_buf_path,
                        svf_n_dir=svf_n_dir,
                        svf_r_max=svf_r_max,
                        svf_noise=svf_noise,
                        ve_factor=ve_factor,
                    )

                    # Gebuffertes SVF öffnen und die 1km-Kachel über Bounding Box extrahieren
                    with rasterio.open(tmp_svf_buf_path) as src_svf:
                        transform_svf_buf = src_svf.transform
                        svf_nodata = src_svf.nodata

                        full_svf_window = Window(0, 0, src_svf.width, src_svf.height)

                        # Fenster für die "core" 1km-Kachel im gebufferten SVF
                        window_inner: Window = from_bounds(
                            x_min_core, y_min_core, x_max_core, y_max_core, transform=transform_svf_buf
                        )
                        window_inner = window_inner.round_offsets().round_lengths()

                        # Sicherstellen, dass Fenster innerhalb des SVF-Rasters liegt
                        window_inner = window_inner.intersection(full_svf_window)

                        if window_inner.width <= 0 or window_inner.height <= 0:
                            logging.error(
                                f"Tile {tile_id}: inneres Fenster hat nicht-positive Größe, überspringe."
                            )
                            continue

                        svf_inner = src_svf.read(1, window=window_inner)
                        transform_svf_inner = window_transform(window_inner, transform_svf_buf)

                        # Output-Dateiname, z.B. svf_2595-1223.tif
                        out_path = os.path.join(out_dir, f"svf_{tile_id}.tif")

                        out_profile = src_svf.profile.copy()
                        out_profile.update(
                            {
                                "height": svf_inner.shape[0],
                                "width": svf_inner.shape[1],
                                "transform": transform_svf_inner,
                                "dtype": svf_inner.dtype,
                                "nodata": svf_nodata,
                                "compress": "LZW",
                            }
                        )

                        with rasterio.open(out_path, "w", **out_profile) as dst_out:
                            dst_out.write(svf_inner, 1)

                        logging.info(f"Tile {tile_id}: gespeichert nach {out_path}")


def main():
    parser = argparse.ArgumentParser(
        description="Berechnet SVF (8-bit) für 1km-Kacheln aus einem großen COG-DEM basierend auf einer 1-Spalten-CSV."
    )
    parser.add_argument(
        "--dem",
        required=True,
        help="Pfad zum großen DEM (COG-TIFF), z.B. ch.swisstopo.lidar_2023.dsm.tif",
    )
    parser.add_argument(
        "--csv",
        required=True,
        help="Pfad zur CSV-Datei mit 1km-Kacheln (eine Spalte, z.B. URLs mit 2595-1223 usw.).",
    )
    parser.add_argument(
        "--out-dir",
        required=True,
        help="Output-Directory für die SVF-1km-Kacheln.",
    )
    parser.add_argument(
        "--buffer-m",
        type=float,
        default=100.0,
        help="Buffer in Metern um die 1km-Kachel (Standard: 100 m).",
    )
    parser.add_argument(
        "--tile-size-m",
        type=float,
        default=1000.0,
        help="Kachelgröße in Metern (Standard: 1000 m).",
    )
    parser.add_argument(
        "--svf-n-dir",
        type=int,
        default=16,
        help="Anzahl Richtungen für SVF (Standard: 16).",
    )
    parser.add_argument(
        "--svf-r-max",
        type=int,
        default=10,
        help="Maximaler Suchradius in Pixeln (Standard: 10).",
    )
    parser.add_argument(
        "--svf-noise",
        type=int,
        default=0,
        choices=[0, 1, 2, 3],
        help="Rauschunterdrückung: 0=aus, 1=niedrig, 2=mittel, 3=hoch (Standard: 0).",
    )
    parser.add_argument(
        "--ve-factor",
        type=float,
        default=1.0,
        help="Vertikaler Überhöhungsfaktor (Standard: 1.0).",
    )
    parser.add_argument(
        "--log-level",
        default="INFO",
        help="Logging-Level (DEBUG, INFO, WARNING, ERROR).",
    )

    args = parser.parse_args()
    logging.basicConfig(
        level=getattr(logging, args.log_level.upper(), logging.INFO),
        format="%(asctime)s [%(levelname)s] %(message)s",
    )

    process_tiles(
        dem_path=args.dem,
        csv_path=args.csv,
        out_dir=args.out_dir,
        buffer_m=args.buffer_m,
        tile_size_m=args.tile_size_m,
        svf_n_dir=args.svf_n_dir,
        svf_r_max=args.svf_r_max,
        svf_noise=args.svf_noise,
        ve_factor=args.ve_factor,
    )


if __name__ == "__main__":
    main()
