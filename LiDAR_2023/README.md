# LiDAR

Berechnungen verschiedener Produkte aus den rohen LiDAR-Daten. Einzig die nDTM (Gebäude und Vegetation) sind produktionsreif.


Linux: https://docs.anaconda.com/free/miniconda/

```
conda create --name pdal-java
```

```
conda activate pdal-java
```

```
conda install -c conda-forge pdal=2.6.2 python-pdal gdal
```
```
conda install -c conda-forge pdal=2.6.3 python-pdal gdal
```

```
nohup jbang preprocess.java > log.log 2>&1 &
```





```
sudo ln -s miniconda3 miniconda
```

## sky view factor

```
conda create --name gdal-python
```

```
conda activate gdal-python
```

```
conda install -c conda-forge python gdal
```

```
cd SkyViewFactor
```

```
python3 -m venv .venv
```

```
source .venv/bin/activate
```

```
pip install rvt-py
```


/Users/stefan/Downloads/swisssurface3d-raster_2023_2605-1229_0.5_2056_5728.tif


python svf_from_geotiff.py /Users/stefan/Downloads/swisssurface3d-raster_2023_2605-1229_0.5_2056_5728.tif /Users/stefan/tmp/svf/01.tif --svf-r-max 15 --svf-n-dir 16 --svf-noise 0

python svf_from_geotiff.py /Users/stefan/Downloads/swisssurface3d-raster_2023_2605-1229_0.5_2056_5728.tif /Users/stefan/tmp/svf/01.tif --svf-r-max 1000 --svf-n-dir 32 --svf-noise 0


python batch_svf_tiles.py \
  --dem /Users/stefan/Downloads/ch.swisstopo.lidar_2023.dsm.tif \
  --csv /Users/stefan/sources/p-agi_lidar_2023/TimeInDaylight/ch.swisstopo.swisssurface3d-raster-LL3uCeEV.csv \
  --out-dir /Users/stefan/Downloads/svf_1km_tiles \
  --svf-r-max 100 --svf-noise 0 --ve-factor 1.2 --svf-n-dir 16


 python batch_svf_tiles.py \
  --dem /Users/stefan/Downloads/ch.swisstopo.lidar_2023.dsm.tif \
  --csv /Users/stefan/sources/p-agi_lidar_2023/TimeInDaylight/ch.swisstopo.swisssurface3d-raster-LL3uCeEV.csv \
  --out-dir /Users/stefan/Downloads/svf_1km_tiles \
  --svf-r-max 100 --svf-noise 0 --ve-factor 1.2 --svf-n-dir 64