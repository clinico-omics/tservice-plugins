<h1 align="center">Plugins for Tservice</h1>
<div align="center">
Tservice is a tool service for reporting, statistics, converting or such tasks that are running less than 10 minutes and more than 1 minutes. 

The repo is hosting all plugins for tservice.
</div>

<div align="center">

[![License](https://img.shields.io/npm/l/package.json.svg?style=flat)](./LICENSE)

</div>

Three Category for Plugins: Tool, Report, Chart

## Table of contents

- [Tservice Plugins (RECOMMENDATION)](#tservice-plugins-recommendation)
  - [Architecture of Tservice Plugin System](#architecture-of-tservice-plugin-system)
  - [How to install plugins for tservice](#how-to-install-plugins-for-tservice)
    - [R Packages](#r-packages)
    - [Python Packages](#python-packages)
- [API Sepcification](#api-sepcification)
- [Contributors](#contributors)

## Tservice Plugins (RECOMMENDATION)

### Architecture of Tservice Plugin System

```
Executable entrypoint file --->|
Tservice Common Library    --->| --> wrapper --> plugin --> Tservice Loader --> Tservice API
Plugin Library file        --->|

├── external                        # All external program, such as python/R/bash/rust program.
│   ├── README.md
│   ├── sigma                       # Executable entrypoint files which are related with plugins.
├── plugins                         # Definition file for plugin
│   ├── config                      # Config file for wrapper/plugin.
│   ├── docs                        # Documentation file for plugin.
│   ├── libs                        # Library for plugin or wrapper.
│   ├── wrappers                    # A wrapper for executable entrypoint file
│   ├── tools
│   ├── reports
|   ├── charts.clj
├── poetry.lock                     # Dependencies for python program by poetry
├── pyproject.toml                  # Config file for poetry
├── README.md
├── version
```

### How to install plugins for tservice

#### R Packages

- Connect tservice container

```
docker exec -it XXX bash
```

- Change directory to /tservice-plugins

```
cd /tservice-plugins/external/XXX
```

- Activate renv and restore all installed packages

```
renv::restore()
```

#### Python Packages

- Connect tservice container

```
docker exec -it XXX bash
```

- Change directory to /tservice-plugins

```
cd /tservice-plugins
```

- Activate renv and restore all installed packages

```
source .env/bin/activate
poetry install
```

### List of plugins

#### xps2pdf

Plugin xps2pdf is depend on `libgxps` tools, so need to install libgxps tools before using xps2pdf plugin.

##### Installation

> For Mac, `brew install libgxps`, more details in https://formulae.brew.sh/formula/libgxps
> For Linux, `yum install libgxps-tools`

```
# Launch base environment
conda activate .env

# Install libgxps
conda install libgxps
```

##### Usage

```bash
# Not need another bash wrapper
```

OR

```clojure
(require '[xps :refer [command]])

(command from to "pdf")
; or
(xps2pdf from to)
```

## API Sepcification

### Request

- filepath
- parameters
- metadata

### Response

- results
- log
- report
- id

## Contributors

- [Jingcheng Yang](https://github.com/yjcyxky)
- [Jun Shang](https://github.com/stead99)
- [Yaqing Liu](https://github.com/lyaqing)
