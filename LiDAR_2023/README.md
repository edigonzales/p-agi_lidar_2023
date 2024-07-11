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

