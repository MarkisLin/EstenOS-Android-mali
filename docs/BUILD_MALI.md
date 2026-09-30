# Compilación de DroidDeck Mali

## Requisitos

- JDK 17
- Gradle 8.10.2 (el wrapper verifica SHA-256)
- Android SDK Platform 34
- Android Build Tools 34.0.0
- Android NDK 27.3.13750724
- CMake 3.22.1

## Compilación local

```bash
chmod +x ./gradlew ./tools/gradle/bootstrap-wrapper.sh
./gradlew :app:assembleDebug -PndkVersion=27.3.13750724 --stacktrace
```

Si el ZIP de fuentes no contiene `gradle/wrapper/gradle-wrapper.jar`, `./gradlew` ejecuta automáticamente `tools/gradle/bootstrap-wrapper.sh`. El bootstrap descarga únicamente el wrapper de Gradle 8.10.2 y exige el SHA-256 `2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046`; si no coincide, la compilación se detiene.

El APK resultante queda en `app/build/outputs/apk/debug/`.

## GitHub Actions

El workflow `.github/workflows/build-mali.yml` restaura/verifica el Gradle Wrapper cuando haga falta, instala automáticamente el SDK, NDK y CMake requeridos y publica el APK como artefacto de la ejecución.
