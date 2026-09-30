<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="artwork/droiddeck-banner-dark.svg">
    <img alt="DroidDeck" src="artwork/droiddeck-banner-light.svg" width="100%">
  </picture>
</p>

# DroidDeck Mali-first

Este fork lleva la experiencia de **Steam en Android** a una arquitectura centrada en GPU ARM Mali.
Reutiliza la base de DroidDeck, pero separa las dependencias históricas de Adreno/Turnip/KGSL y
construye rutas explícitas para Mali DRM y para teléfonos Android stock con `mali_kbase` (`/dev/mali0`).

La edición está simplificada deliberadamente: **Steam · Biblioteca · Ajustes**. No incluye catálogo
de emuladores, escaneo de ROMs ni un escritorio Linux hospedado.

## Estado de compatibilidad Mali

DroidDeck no considera que “Mali” sea una sola ABI. El launcher prueba el backend que el kernel
expone, negocia Kbase cuando existe `/dev/mali0` y comprueba el perfil del ICD antes de iniciar la
sesión.

| Perfil de referencia | Backend | Estado actual |
| --- | --- | --- |
| Mali-G615 / Pan v11 / CSF / Kbase 1.21 | `/dev/mali0` | perfil glibc PanVK-Kbase integrado |
| Mali-G720 / Pan v12 / CSF / Kbase 1.30 | `/dev/mali0` | reconocido; experimental/manual |
| Mali-G52 r1 / Pan v7 / JM / Kbase 11.38 | `/dev/mali0` | reconocido; experimental/manual |
| Mali con render node DRM accesible | `/dev/dri/renderD*` | ruta preparada para PanVK/Panfrost/Panthor |

Que Vulkan enumere la GPU **no significa** que todos los juegos de Windows funcionen. DXVK,
VKD3D-Proton, Zink y gamescope dependen de las capacidades reales del driver de cada perfil. El
runtime rechaza lavapipe/llvmpipe como sustituto de una GPU Mali funcional.

Adreno/Turnip permanece como ruta secundaria de compatibilidad del código heredado; el trabajo nuevo
del fork se orienta a Mali.

## Flujo gráfico

En Mali Android stock, el objetivo es:

```text
Steam / Proton
      ↓
DXVK / VKD3D-Proton / Zink
      ↓
ICD PanVK glibc compatible con el perfil
      ↓
/dev/mali0 (Kbase) o /dev/dri/renderD* (DRM)
      ↓
GPU Mali
      ↓
gamescope → compositor Android → SurfaceControl
```

Antes de entregar la sesión a Steam, DroidDeck puede comprobar dependencias glibc, enumeración
Vulkan, Zink/EGL y un gamescope mínimo. Los fallos se separan por capa para evitar diagnosticar todo
como “pantalla negra”.

## Interfaz

La interfaz de este fork está orientada al español y a Steam. Los términos que son identificadores
técnicos (`Vulkan`, `Kbase`, `CSF`, `JM`, `UAPI`, `Zink`, `gamescope`, nombres `.so` y variables de
entorno) se mantienen sin traducir cuando cambiarlos rompería protocolos o dificultaría el
diagnóstico.

Los componentes necesarios para Steam —runtime Linux, Proton ARM64, FEX, DXVK, VKD3D-Proton y
controladores Vulkan— se administran desde **Ajustes**. En hardware Mali/Kbase ya calificado, el
primer inicio intenta preparar automáticamente el runtime, el PanVK-Kbase exacto y Proton antes de
abrir Steam; no hace falta entrar al gestor de controladores.

El perfil **Automático** mantiene 720p como base: 720p/30 FPS en el nivel mínimo de 4 GB o con
presión térmica severa, 720p/45 en la ruta equilibrada y 720p/60 cuando hay margen suficiente.
Calidad (hasta 900p) queda como elección manual. Los ajustes técnicos permanecen disponibles, pero
ocultos por defecto detrás de opciones avanzadas.

## Construcción

El proyecto conserva `tools/build_local.sh` y un workflow Android reproducible. La configuración del
wrapper exige Gradle 8.10.2 con su SHA-256 esperado para impedir usar una distribución incompleta o
modificada por accidente.

Esta etapa del fork se está afinando primero a nivel de código y pruebas estáticas; no se considera
un APK “validado en Mali” hasta probar el pipeline en hardware real y por perfil.

## Diagnóstico

Los registros normales permanecen en la caché privada para no pedir permisos al primer arranque.
Si el usuario activa **Registros de sesión**, se conservan en `Download/DroidDeck/`. Para Mali
incluyen, cuando están disponibles:

- backend guest (`mali-kbase` o `mali-drm`);
- frontend Kbase (`CSF` o `JM`);
- versión UAPI;
- Product ID canónico y GPU ID completo;
- perfil Mali reconocido;
- ICD Vulkan elegido por el modo **Automático**;
- resultado del preflight Vulkan/Zink/gamescope.

Consulta [`docs/MALI_PORT.md`](docs/MALI_PORT.md) para la arquitectura y las invariantes del port.

## Licencia y créditos

GPL-3.0. El runtime, shims, entrada y trabajo de controladores reutilizan componentes del proyecto
original DroidDeck, WinNative y Bannerlator. Steam y Proton pertenecen a Valve Corporation; este
proyecto no está afiliado a Valve.
