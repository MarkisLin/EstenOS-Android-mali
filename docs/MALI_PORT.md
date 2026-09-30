# Port Mali-first de DroidDeck

Este fork es intencionalmente **Mali-first**, **Steam-only** y con la interfaz en español. La ruta
Adreno/Turnip se conserva como compatibilidad secundaria, pero la arquitectura nueva evita asumir
KGSL/Freedreno y trata el host Android y el guest Linux como dos problemas gráficos distintos.

Un teléfono puede dibujar la interfaz Android de DroidDeck con el Vulkan propietario de Mali y, al
mismo tiempo, no disponer todavía de un ICD Vulkan glibc utilizable dentro del guest. Por eso el
soporte Mali se decide por backend, UAPI y perfil real de la GPU, no solo por el nombre comercial.

## Stage 1 — separación host/backend

### Compositor Android

- `DeviceSupport` distingue `MALI`, `ADRENO`, `XCLIPSE`, `POWERVR` y `UNKNOWN`.
- Adreno conserva Turnip; Mali usa el **Vulkan del sistema Android**.
- Las extensiones opcionales dma-buf/modifier se consultan antes de habilitarlas.
- La ausencia de importación dma-buf ya no impide crear el dispositivo Vulkan del host.
- Un `AHardwareBuffer` válido puede seguir presentándose mediante SurfaceControl aunque Vulkan no
  pueda importar su dma-buf asociado.

### Guest Linux

- Mali nunca selecciona Freedreno silenciosamente.
- Los controladores importados pueden ser ICDs `libvulkan_*.so` AArch64 **glibc** genéricos.
- Las bibliotecas auxiliares `.so` del ICD se conservan y se exponen mediante `LD_LIBRARY_PATH`.
- Los HAL Vulkan Android/Bionic se rechazan para el guest glibc.
- `TU_DEBUG` solo se aplica a la ruta Turnip/Freedreno.

## Stage 2 — separación por ABI del kernel

`DeviceSupport.GuestGpuBackend` representa la interfaz GPU que el guest realmente puede utilizar:

- `ADRENO_KGSL`: Qualcomm KGSL. Solo esta ruta recibe el shim de render node de DroidDeck.
- `MALI_DRM`: existe un `/dev/dri/renderD*` que la aplicación puede abrir. Es la ruta DRM de
  Panfrost/Panthor + PanVK.
- `MALI_KBASE`: `/dev/mali0` de ARM `mali_kbase` es utilizable.
- `NONE`: no hay una interfaz GPU guest actualmente soportada.

El render node DRM se abre con `O_RDWR`; ver únicamente la ruta no cuenta como soporte. En Mali no
se fabrica nunca un nodo KGSL/DRM falso.

## Stage 3 — Kbase directo para Mali stock

La ruta principal para Android stock con `/dev/mali0` dejó de ser un futuro RPC genérico. DroidDeck
está preparado para un PanVK compilado como **ICD AArch64 glibc** que hable directamente con la UAPI
`mali_kbase`. La implementación pública de referencia inicial es PanVK-Kbase para G615.

### Probe Kbase nativo

`MaliKbaseProbe` abre temporalmente `/dev/mali0` y:

1. intenta `VERSION_CHECK` CSF;
2. si no corresponde, intenta `VERSION_CHECK` JM;
3. consulta `GET_GPUPROPS` cuando el kernel lo permite;
4. lee por separado `PRODUCT_ID`, `RAW_GPU_ID` (propiedad Kbase 55) y la máscara de shaders;
5. cierra el descriptor sin crear colas ni enviar trabajo a la GPU.

`MALI_KBASE` solo se habilita si el handshake funciona. La mera existencia de `/dev/mali0` no basta.
El Product ID canónico se usa para el matching; el GPU ID completo se conserva para diagnóstico.
Por ejemplo, `0xb8a31030` se normaliza a `0xb8a3` y `0xc8700010` a `0xc870`.

### Contrato del ICD Kbase

El esquema de paquete 4 puede declarar, entre otros campos:

```json
{
  "schemaVersion": 4,
  "kind": "linux-vulkan-icd",
  "abi": "linux-aarch64-glibc",
  "guestBackend": "mali-kbase",
  "maliKbaseFrontend": "csf",
  "maliKbaseUapiMajor": 1,
  "maliKbaseMinUapiMinor": 21,
  "maliKbaseMaxUapiMinor": 21,
  "maliProductIds": ["0xb8a3"]
}
```

Antes de iniciar una sesión se comparan backend, CSF/JM, rango UAPI y Product ID con el probe real.
Un paquete incompatible se rechaza antes de gamescope. Los nombres históricos `mali-proprietary` y
`mali-bridge` se normalizan a `mali-kbase` al leer paquetes antiguos.

### Empaquetado

`tools/mali/package_kbase_driver.py` valida AArch64 + glibc, permite helpers `.so`, calcula SHA-256
y genera un ZIP importable. Un ejemplo para G615 es:

```sh
python3 tools/mali/package_kbase_driver.py \
  --lib dist/libvulkan_panfrost.so \
  --out panvk-kbase-g615.zip \
  --name "PanVK Kbase G615" \
  --version "0.1.0-beta.3" \
  --profile g615-v11-csf --pan-arch 11 \
  --frontend csf --uapi 1.21 --product-id 0xb8a3
```

## Stage 4 — releases calificadas y preflight del guest

La pantalla de controladores puede consultar releases PanVK-Kbase, pero solo ofrece automáticamente
assets Linux/glibc cuyo tag inmutable corresponda a un perfil calificado. Android/Bionic y perfiles
desconocidos quedan fuera.

El primer perfil integrado es `g615-v11-csf`: Mali-G615, Pan v11, CSF, Kbase UAPI 1.21 y Product ID
`0xb8a3`. La descarga conserva verificación SHA-256 y pasa después por el importador AArch64/glibc.

Antes de gamescope, el runtime puede ejecutar:

1. `ldd` para detectar dependencias glibc faltantes;
2. comprobación de que `/dev/mali0` está realmente dentro del guest;
3. `vulkaninfo --summary`, cuando existe, para probar enumeración Vulkan;
4. Zink/EGL surfaceless, cuando `eglinfo` está disponible;
5. un gamescope mínimo para comprobar la ruta Wayland.

## Stage 5 — Steam-only y español

El producto visible deja de ser un frontend multipropósito. El rail principal es únicamente:

- **Steam**
- **Biblioteca**
- **Ajustes**

El catálogo de RPCS3, Dolphin, Cemu, DuckStation, PPSSPP, RetroArch y otros emuladores se elimina del
flujo. También se retiran el escaneo/montaje de ROMs y las rutas de escritorio hospedado. Los
componentes necesarios para Steam —Proton, FEX, DXVK, VKD3D-Proton y controladores— permanecen en
Ajustes.

Menús, diálogos, gestor de archivos, estados de instalación y errores se muestran en español. Los
identificadores de APIs, variables de entorno, nombres `.so`, `CSF`, `JM`, `UAPI`, `Vulkan`, `Zink`
y `gamescope` se conservan literalmente cuando forman parte del protocolo o del diagnóstico.

### Códigos del preflight Mali

- `77`: `/dev/mali0` no llegó al guest.
- `78`: faltan dependencias glibc del ICD.
- `79`: PanVK/Vulkan no pudo inicializarse.
- `80`: Vulkan funciona pero Zink no crea el contexto gráfico.
- `81`: Vulkan/Zink funcionan pero falla la prueba mínima de gamescope/Wayland.
- `82`: se detectó lavapipe/llvmpipe u otro fallback Vulkan por CPU; no cuenta como aceleración Mali.

El marcador de preflight se guarda por combinación de frontend, Product ID, UAPI, perfil e SHA-256
del ICD. Cambiar de GPU o controlador obliga a validar de nuevo.

## Stage 6 — matching estricto, automático real y poda interna

### Matriz de madurez

`MaliKbaseProfiles` distingue `QUALIFIED_GLIBC`, `PUBLIC_EXPERIMENTAL`, `BRINGUP` y `UNKNOWN`.
Actualmente se registran estos perfiles de referencia:

| Perfil | Backend | UAPI | Product ID | Estado en DroidDeck |
| --- | --- | --- | --- | --- |
| Mali-G615 / Pan v11 | CSF | 1.21 | `0xb8a3` | glibc calificado e integrado |
| Mali-G720 / Pan v12 | CSF | 1.30 | `0xc870` | reconocido, experimental, instalación manual |
| Mali-G52 r1 / Pan v7 | JM | 11.38 | `0x7402` | reconocido, experimental, instalación manual |

G720 y G52 **no** se convierten en descargas automáticas solo porque exista trabajo público. Para eso
se exige un paquete glibc reproducible y un contrato de compatibilidad adecuado al guest.

### Selección `Automático`

En Mali, si no hay una selección manual, DroidDeck busca entre los ICD instalados y solo considera
los que declaren exactamente `mali-kbase` o `mali-drm` según el backend actual. Después filtra por
frontend, UAPI y Product ID. Un paquete ambiguo o heredado sin backend claro nunca se auto-selecciona.
La selección manual conserva prioridad, pero tampoco puede saltarse la comprobación de compatibilidad.

En un teléfono Mali, la UI no consulta ni ofrece releases Turnip de Adreno. El compositor Android
usa **Vulkan del sistema** y el guest usa únicamente ICDs Mali glibc compatibles.

### Steam-only también internamente

Se retiraron del runtime/build activos:

- assets y launchers LXQt/labwc;
- wlroots parcheado y su workflow de construcción;
- Firefox del antiguo escritorio hospedado;
- el catálogo remoto multipropósito y su descarga en segundo plano;
- helpers de ROMs y configuradores específicos de RPCS3, Dolphin, Cemu, DuckStation, melonDS,
  PPSSPP, RetroArch y otros emuladores;
- el observador Android que esperaba solicitudes de cambio al escritorio.

Los valores heredados `desktop`/`lxqt` se conservan únicamente como aliases de migración y se
redirigen inmediatamente a Steam. `BL_STEAM_UI=desktop` es distinto: significa la interfaz clásica
del **cliente Steam**, no un escritorio Linux hospedado, por lo que sigue siendo válido.

## Invariantes que no deben romperse

- No seleccionar Turnip por el simple hecho de usar una versión Android reciente.
- No fabricar un render node KGSL en Mali.
- No cargar Freedreno automáticamente en una GPU Mali conocida.
- No tratar PanVK DRM y PanVK Kbase como intercambiables.
- No considerar `/dev/mali0` utilizable sin handshake Kbase.
- No considerar lavapipe/llvmpipe como soporte Mali funcional.
- No exigir importación dma-buf/modifier del Vulkan host para arrancar el compositor.
- Mantener la presentación directa de `AHardwareBuffer` cuando la importación dma-buf no sea viable.
- Mantener separados los ICD guest Linux/glibc y los HAL Android/Bionic.
- Preferir perfiles exactos por GPU/UAPI antes que declarar un binario Mali como universal.
- No reintroducir catálogos de emuladores ni escritorio hospedado en la edición Steam-only.

## Estado de validación de Stage 6

Esta etapa se valida sin generar APK, deliberadamente, porque el usuario pidió continuar afinando el
código antes de compilar. Se ejecutan comprobaciones estáticas y aisladas sobre:

- `bash -n` de los scripts de sesión/build;
- sintaxis C del probe Kbase con `-Wall -Wextra -Werror`;
- compilación aislada de las clases Java de perfiles/selección de ICD;
- parseo de XML, YAML y perfiles JSON;
- pruebas de normalización de Product ID y matching G615/G720/G52;
- round-trip del empaquetador AArch64/glibc y hashes del paquete;
- búsqueda de referencias activas a los assets/escritorio/emuladores eliminados.

Una compilación Android/NDK completa queda para una etapa posterior, cuando decidamos congelar esta
arquitectura y probarla en dispositivos Mali reales.

## Stage 6 — móvil primero: instalación y rendimiento automáticos

El objetivo de esta etapa es que el usuario no tenga que entender Kbase, ICDs, FEX o gamescope para
abrir Steam. La ruta normal pasa a ser:

`abrir DroidDeck → preparar lo que falte → Steam`

En una Mali/Kbase con perfil glibc calificado, el primer inicio puede encadenar automáticamente:

1. entorno Linux;
2. PanVK-Kbase exacto para el perfil detectado (release con SHA-256 verificado);
3. seed ARM64 de Proton;
4. gamescope y Steam.

Un perfil desconocido o todavía experimental **no** recibe un driver al azar. La instalación automática
se detiene con una explicación y una importación manual compatible sigue siendo posible.

### Perfil móvil automático

`MobilePerformance` añade una política conservadora que solo usa señales estables de Android; no intenta
adivinar potencia por el nombre comercial del SoC:

- **Ahorro:** 720p / 30 FPS, `MALLOC_ARENA_MAX=2`; automático en el nivel de 4 GB, low-RAM o temperatura severa.
- **Equilibrado:** 720p / 45 FPS; ruta automática normal para teléfonos con margen intermedio o temperatura moderada.
- **Fluido:** 720p / 60 FPS; automático solo en el nivel de memoria amplio y sin presión térmica importante, o seleccionable manualmente.
- **Calidad:** hasta 900p y frecuencia de la sesión; solo manual.

Una resolución elegida explícitamente por el usuario siempre gana. El perfil Automático nunca sube por sí
solo a Calidad: tener mucha RAM no demuestra rendimiento GPU sostenido ni margen térmico. El requisito mínimo
del proyecto es 4 GB nominales; Android puede reportar algo menos por memoria reservada, por lo que el gate
usa un umbral visible conservador que distingue teléfonos nominales de 4 GB frente a equipos de 2–3 GB.

La motivación es especialmente importante en Mali integrado: GPU y CPU comparten la RAM del sistema, y
Steam añade CEF/FEX/gamescope antes de que el juego empiece. Reducir píxeles y limitar FPS suele comprar
más estabilidad que flags de driver agresivos que cambian semántica o compatibilidad.

### Primera ejecución y ahorro de recursos

La ruta automática también evita trabajo que no ayuda a jugar:

- el runtime grande reintenta hasta tres veces y reanuda una descarga parcial cuando el servidor
  admite `Range`;
- los paquetes Mali pequeños se descargan a `.part`, se comprueba tamaño + SHA-256 y solo después se
  renombran/importan;
- no se solicitan permisos de almacenamiento ni micrófono al abrir la aplicación; aparecen solo al
  activar registros en Descargas, abrir archivos o habilitar el micrófono;
- en **Ahorro**, `mangoapp` no se inicia aunque Steam Deck mode esté activo, para reservar memoria y
  CPU al cliente y al juego;
- **Ahorro** y **Equilibrado** prefieren una modalidad de panel de hasta 60 Hz; **Calidad** puede usar
  la frecuencia máxima. Esto evita mantener 90/120/144 Hz cuando gamescope ya limita a 30/60 FPS.

Los ajustes técnicos de núcleos, Zink, seccomp y hostname quedan detrás de **Mostrar ajustes técnicos**.
La fila de Turnip/sysmem se oculta completamente en Mali.


## Stage 7 — calidad base 720p, mínimo 4 GB y autoreparación Mali

Stage 7 congela el objetivo de hardware indicado para este fork: **4 GB de RAM nominales como mínimo**.
DroidDeck ya no intenta convertir teléfonos de 2–3 GB en objetivo bajando agresivamente la calidad. Si Android
reporta una cantidad de RAM incompatible con un teléfono nominal de 4 GB, el inicio de Steam se detiene con
una explicación en español antes de descargar o abrir el runtime.

La política automática también cambia de prioridad: se reduce primero el trabajo temporal y el overhead de
procesos, no la resolución. El nivel mínimo soportado conserva 720p/30; el nivel equilibrado usa 720p/45; y
un teléfono con amplio margen de memoria y sin presión térmica arranca a 720p/60. 900p continúa siendo una
opción manual. 540p sigue disponible solo como escape manual para un juego especialmente pesado.

La ruta de controlador Mali corrige dos fallos de recuperación:

1. una selección explícita vieja, eliminada o incompatible se limpia antes de resolver `Automático`, evitando
   que un ICD automático válido quede oculto por una preferencia rota;
2. si un ICD Mali descargado automáticamente falla el preflight con códigos 78, 79 o 82, DroidDeck elimina
   exclusivamente ese paquete administrado, olvida su registro y permite **una sola reinstalación limpia**.
   Las selecciones manuales nunca se borran y el segundo fallo se muestra al usuario, evitando bucles.

La combinación conserva la filosofía `abrir DroidDeck → preparar lo que falte → Steam` sin sacrificar 720p
para perseguir hardware fuera del mínimo del proyecto.
