#!/usr/bin/env python
"""
Berechnet den Sky View Factor (SVF) aus einem DEM-GeoTIFF
und speichert das Ergebnis wieder als GeoTIFF.

Benötigte Pakete:
    pip install rvt-py numpy

Aufruf:
    python svf_from_geotiff.py input_dem.tif output_svf.tif \
        --svf-r-max 10 --svf-n-dir 16 --svf-noise 0
"""

import argparse
import numpy as np
import rvt.vis
import rvt.default


def compute_svf(
    dem_path: str,
    out_path: str,
    svf_n_dir: int = 16,
    svf_r_max: int = 10,
    svf_noise: int = 0,
    ve_factor: float = 1.0,
) -> None:
    # DEM als Array + Metadaten laden
    dem_dict = rvt.default.get_raster_arr(dem_path)
    dem_arr = dem_dict["array"]
    dem_res_x, dem_res_y = dem_dict["resolution"]
    dem_no_data = dem_dict["no_data"]

    # Sky View Factor berechnen (nur SVF, kein ASVF / Openness)
    svf_dict = rvt.vis.sky_view_factor(
        dem=dem_arr,
        resolution=dem_res_x,          # Pixelgröße (in m o.ä.)
        compute_svf=True,
        compute_asvf=False,
        compute_opns=False,
        svf_n_dir=svf_n_dir,
        svf_r_max=svf_r_max,
        svf_noise=svf_noise,
        ve_factor=ve_factor,
        no_data=dem_no_data,
    )

    svf_arr = svf_dict["svf"]  # 2D-Array mit SVF-Werten (0–1)

    # Als GeoTIFF speichern (float32, NoData = NaN)
    rvt.default.save_raster(
        src_raster_path=dem_path,   # Geometrie/CRS vom Input übernehmen
        out_raster_path=out_path,
        out_raster_arr=svf_arr,
        no_data=np.nan,
        e_type=6,                   # GDAL Float32
    )

def compute_svf_8bit(
    dem_path: str,
    out_path: str,
    svf_n_dir: int = 16,
    svf_r_max: int = 10,
    svf_noise: int = 0,
    ve_factor: float = 1.0,
) -> None:
    # DEM laden
    dem_dict = rvt.default.get_raster_arr(dem_path)
    dem_arr = dem_dict["array"]
    dem_res_x, dem_res_y = dem_dict["resolution"]
    dem_no_data = dem_dict["no_data"]

    # SVF berechnen (nur SVF)
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

    # NoData-Maske (rvt setzt typischerweise NaN für NoData)
    nodata_mask = np.isnan(svf_arr)

    # Werte begrenzen und auf 0–255 skalieren
    svf_arr = np.clip(svf_arr, 0.0, 1.0)
    svf_8bit = (svf_arr * 255.0).round().astype(np.uint8)

    # Einen 8-bit-NoData-Wert wählen (z.B. 0) und anwenden
    svf_nodata_8bit = 0
    svf_8bit[nodata_mask] = svf_nodata_8bit

    # Als 8-bit-GeoTIFF speichern (GDAL GDT_Byte -> e_type=1)
    rvt.default.save_raster(
        src_raster_path=dem_path,
        out_raster_path=out_path,
        out_raster_arr=svf_8bit,
        no_data=svf_nodata_8bit,
        e_type=1,  # Byte
    )

def main():
    parser = argparse.ArgumentParser(
        description="Berechnet Sky View Factor (SVF) aus einem DEM-GeoTIFF."
    )
    parser.add_argument("input_dem", help="Pfad zum Eingabe-DEM (GeoTIFF)")
    parser.add_argument("output_svf", help="Pfad zum Ausgabe-SVF (GeoTIFF)")

    parser.add_argument(
        "--svf-n-dir",
        type=int,
        default=16,
        help="Anzahl der Richtungen für SVF (Standard: 16)",
    )
    parser.add_argument(
        "--svf-r-max",
        type=int,
        default=10,
        help="Maximale Suchradius in Pixeln (Standard: 10)",
    )
    parser.add_argument(
        "--svf-noise",
        type=int,
        default=0,
        choices=[0, 1, 2, 3],
        help="Rauschunterdrückung: 0=aus, 1=niedrig, 2=mittel, 3=hoch (Standard: 0)",
    )
    parser.add_argument(
        "--ve-factor",
        type=float,
        default=1.0,
        help="Vertikaler Überhöhungsfaktor (Standard: 1.0)",
    )

    args = parser.parse_args()

    compute_svf_8bit(
        dem_path=args.input_dem,
        out_path=args.output_svf,
        svf_n_dir=args.svf_n_dir,
        svf_r_max=args.svf_r_max,
        svf_noise=args.svf_noise,
        ve_factor=args.ve_factor,
    )

if __name__ == "__main__":
    main()
