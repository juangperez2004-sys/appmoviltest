# Resumen del proyecto: App de Asistencia por Reconocimiento Facial

> Documento de contexto general del proyecto (proyecto de residencia).

---

## 1. Descripción general

Aplicación **Android** en **Kotlin** para el pase de lista por **reconocimiento facial**:

- Un administrativo abre la app, la cámara detecta el rostro del trabajador y registra su asistencia automáticamente.
- Los trabajadores se pueden **registrar desde el propio celular** (cámara + huella facial) o venir **precargados desde el PC** (galería dentro del APK).
- Los registros del día se pueden **exportar a Excel**.
- Los datos se pueden **sincronizar entre varios celulares** por **WiFi local (sin internet)**.
- Todo funciona **offline** (sin conexión a internet en el uso diario).

**Paquete:** `com.juan.asistenciaapp` · **minSdk 26 · targetSdk 34 · compileSdk 35**

---

## 2. Arquitectura técnica

### 2.1 Redes neuronales (dos)

| Red | Archivo | Librería | Función |
|---|---|---|---|
| **BlazeFace** | `face_detector.tflite` | MediaPipe (`tasks-vision`) | Detecta dónde está el rostro en el fotograma |
| **MobileFaceNet** (w600k_mbf) | `w600k_mbf.onnx` | ONNX Runtime (`onnxruntime-android`) | Convierte el rostro en un vector de 512 números (huella facial) |

- Se cargan **una sola vez** al abrir la app (`Modelos.kt`) y se comparten entre pantallas. No se vuelven a entrenar en el celular: solo **inferencia**.
- Registrar a alguien = calcular y guardar su huella (vector). Reconocer = comparar vectores por **similitud coseno**.

### 2.2 Pipeline de reconocimiento (pestaña Asistencia)

1. Cámara captura el fotograma (CameraX, RGBA 1280x960).
2. **BlazeFace** encuentra el rostro más grande.
3. Se **alinea** a 112x112 (referencia ArcFace).
4. Se descartan fotogramas borrosos (varianza del Laplaciano).
5. Promedio de píxeles de 6 fotogramas (anti-ruido de cámara).
6. **MobileFaceNet** calcula el embedding 512-d.
7. `Gallery.buscar` compara contra todas las huellas (umbrales + margen adaptativo).
8. Al confirmar N fotogramas seguidos, se registra la asistencia (una vez por persona/día).

### 2.3 Base de datos (SQLite, `AttendanceDb` — versión 6)

| Tabla | Contenido |
|---|---|
| `registros` | Asistencias: fecha, hora, nombre (único fecha+nombre, `updated_at`) |
| `trabajadores` | Registrados desde la app: nombre, huella (BLOB), fecha, `updated_at` |
| `renombrados` | Renombres de trabajadores del PC (nombre original → actual, `updated_at`) |
| `ocultos` | Trabajadores del PC eliminados (no vuelven a aparecer) |
| `huellas_actualizadas` | Huellas recalculadas de trabajadores del PC (prioridad sobre el APK, `updated_at`) |
| `borrados` | Tombstones: eliminaciones propagables entre dispositivos (`updated_at`) |

**Migraciones:** v1 → v6. La v6 agrega `updated_at` (para la sincronización) y la tabla `borrados`.

### 2.4 Galería del PC vs. trabajadores de la app

- **Galería del PC** (se genera con `entrenar_modelo.py`/`copiar_fotos_pc.py` en el PC y va **dentro del APK**): `nombres.json` (439 nombres), `embeddings.bin` (matriz 439x512), `assets/fotos/` (441 fotos). Es la misma en todos los dispositivos porque todos instalan el mismo APK.
- **Trabajadores de la app**: viven en SQLite; se suman a la galería de reconocimiento; se sincronizan entre dispositivos.
- La red es **independiente** de la galería: quitar los precargados no rompe el reconocimiento, solo deja la app sin esa lista inicial.

---

## 3. Trabajo realizado (historial de esta sesión)

### 3.1 Corrección del cierre de la app en otro celular

**Problema:** la app se cerraba ("Asistencia se detuvo") al abrirla en el teléfono de una compañera (Android 12+), aunque funcionaba en el teléfono del desarrollador.

**Diagnóstico:** el cierre era un **crash nativo (SIGSEGV)** al cargar las librerías de modelos (todo lo que es excepción Java ya estaba atrapado y no cierra la app). Lo más probable: MediaPipe.

**Cambios:**
- `build.gradle.kts`: MediaPipe `0.10.14` → **`0.10.9`** (versión estable).
- `Diag.kt` (nuevo): escribe pasos de carga y crashes en `Descargas/AsistenciaDiag/diag.txt` para diagnosticar sin PC.
- `Modelos.kt`: carga de ONNX y MediaPipe **por separado** (una falla no bloquea la otra) + registro de pasos.
- `MainActivity.kt`: manejador global de crash Java → `diag.txt`.
- `Gallery.kt`: carga defensiva de assets (un fallo ya no cierra la app).

**Pendiente:** confirmar en el teléfono de la compañera con el APK nuevo.

### 3.2 Sincronización WiFi entre dispositivos (sin internet)

**Requerimiento:** varios celulares administrativos deben quedar con la misma información (trabajadores, asistencias, fotos), estando en el **mismo WiFi**, con un botón que sincronice **todos a la vez** y **sin servidor central fijo**.

**Diseño implementado:**
- Cada dispositivo corre un **servidor HTTP local** (NanoHTTPD, puerto 8555) mientras la app está abierta.
- **Descubrimiento** por broadcast UDP (puerto 8554) + **respaldo por QR** (vincular un dispositivo escaneando su código).
- Un botón **"Sincronizar"** (en Trabajadores) dispara la ronda: baja de todos → fusiona → los demás bajan del iniciador. Todos convergen.
- **Fusión automática (last-write-wins):** gana el `updated_at` más reciente. Asistencias idempotentes (sin duplicados). Tombstones para que un borrado se propague.
- Se sincroniza: trabajadores (nombre + huella), asistencias, renombres, eliminados, huellas sobrescritas y **fotos**.

**Archivos nuevos:**
- `sync/SyncServidor.kt` — servidor HTTP + respuesta UDP + IP local + dispositivos vinculados (SharedPreferences).
- `sync/SyncEngine.kt` — descubrimiento, QR (ZXing), sincronización y `SyncMerge` (serialización/fusión JSON).
- `ui/SincronizarActivity.kt` + `activity_sincronizar.xml` — pantalla de sincronización (QR propio, escanear, botón).
- `ui/EscanearQRActivity.kt` + `activity_escanear_qr.xml` — escáner de QR con CameraX + ZXing.

**Modificados:** `AndroidManifest.xml` (permisos INTERNET/WiFi, `usesCleartextTraffic`, actividades), `AttendanceDb.kt` (v6), `menu_trabajadores.xml` + `TrabajadoresFragment.kt` (botón), `MainActivity.kt` (arranca/detiene el servidor), `build.gradle.kts` (NanoHTTPD + ZXing).

**Estado:** implementado; en la primera prueba reportó "Listo pero sin cambios". Se agregó **diagnóstico por dispositivo** en la pantalla (conexión/error/resumen por IP) y mejora en la detección de IP WiFi. **Requiere re-prueba en 2 celulares** (mismo WiFi, app abierta en ambos).

### 3.3 Optimización de la app

- **Tamaño del APK: 147 MB → 59 MB** — se compila solo para **arm64-v8a** (`abiFilters`), la arquitectura de todos los celulares reales Android 12+ (116 MB de librerías nativas de 4 arquitecturas ya no se incluyen).
- **Rendimiento de cámara**: reuso de buffers en `proxyToBitmap` y `embeddingPromedio` (menos asignaciones por fotograma → menos GC, más fluido en gama baja). Sin cambiar la lógica de reconocimiento.

**Nota:** el APK arm64 **no se instala en emuladores x86** ni celulares 32 bits (para esos habría que generar un APK universal).

### 3.4 Mejoras a la pestaña Historial

- **Buscador por nombre** dentro de los registros del día (filtra en vivo, ignora mayúsculas).
- **Botón corregido:** ahora dice **"Exportar Excel"** (antes "Exportar CSV" pero generaba `.xlsx`).
- **Tocar un registro** abre el **detalle de esa persona** con todo su historial de asistencias (reutiliza `DetalleTrabajadorActivity`).

### 3.5 Pantalla de carga animada (splash)

- Usa el logo de `icono/icono.jpg` (copiado a `res/drawable-nodpi/icono_inicio.jpg`).
- `SplashActivity.kt` + `activity_splash.xml`: logo en **círculo** con borde azul, fondo degradado, animación de zoom + anillo pulsante + nombre deslizándose, y transición a la app.
- `AndroidManifest.xml`: `SplashActivity` es ahora la actividad inicial.

### 3.6 Repositorio Git

- Proyecto subido a **https://github.com/juangperez2004-sys/appmoviltest** (rama `master`).
- Se creó `.gitignore` (excluye `build/`, `.gradle/`, `.idea/`, `local.properties`, `.freebuff/`, APKs).
- Commit inicial: `fbe078d` · Commit de mejoras de UX en sincronización: `e16418e`.

### 3.7 Mejoras de precisión del reconocimiento (puntos 2 y 3)

**Punto 2 — Mejor alta (registro de trabajador)** (`RegistrarTrabajadorActivity.kt`):
- **Validación por captura**: cada foto se revisa con nitidez adaptativa (`esNitidaAdaptativa`); si sale borrosa NO se acepta y se pide repetir ("Foto borrosa. Repítela sin mover el celular.").
- **Mínimo de fotos**: se recomiendan **4** y se exige al menos **3 buenas** para poder guardar (`FOTOS_MINIMAS`).
- **Eliminación de outliers**: al guardar se descartan las huellas que se alejan mucho del promedio (similitud coseno < 0.6) y se promedian las que quedan; así una mala foto no contamina la huella final.

**Punto 3 — Filtros de calidad en el escaneo** (`AsistenciaFragment.kt`):
- **Nitidez adaptativa a la luz**: el escaneo ahora usa `esNitidaAdaptativa` (el umbral del Laplaciano escala con el brillo), aceptando tomas oscuras pero nítidas y siguiendo rechazando el desenfoque real.

**Nota:** las demás mejoras del punto 1 (cambiar el modelo a AdaFace/ArcFace) y del punto 3 (detección de oclusión con Face Mesh, norma del embedding como calidad) quedan **pendientes** por requerir re-generar los embeddings o agregar otro modelo de MediaPipe.

---

## 4. Dependencias principales

| Librería | Versión | Uso |
|---|---|---|
| CameraX (core/camera2/lifecycle/view) | 1.4.2 | Cámara y análisis |
| MediaPipe tasks-vision | **0.10.9** | Detección de rostros (BlazeFace) |
| ONNX Runtime Android | 1.23.0 | Red MobileFaceNet (embeddings) |
| Material Components | 1.12.0 | UI |
| NanoHTTPD | 2.3.1 | Servidor HTTP local (sincronización) |
| ZXing core | 3.5.3 | QR (sincronización) |

---

## 5. Cómo se usa (resumen operativo)

1. **Pase de lista:** abrir la app (cámara) → el trabajador se pone frente a la cámara → se registra su asistencia (una vez al día).
2. **Registrar trabajador:** pestaña Trabajadores → botón "+" → nombre + foto.
3. **Historial:** pestaña Historial → registros del día, buscar por nombre, tocar para ver historial de la persona, exportar Excel.
4. **Sincronizar:** Trabajadores → icono de sincronizar → **"Sincronizar ahora"** (todos en el mismo WiFi y con la app abierta). Vincular por QR la primera vez si hace falta.

---

## 6. Pendientes / notas

- [ ] **Re-probar la sincronización** en 2 celulares con el APK nuevo y leer el mensaje final por dispositivo (y `diag.txt` si falla).
- [ ] Confirmar que la corrección de MediaPipe (0.10.9) resolvió el cierre en el teléfono de la compañera.
- [ ] (Precisión, opcional) Punto 1: cambiar a un modelo mejor (AdaFace/ArcFace) re-generando los embeddings en el PC.
- [ ] (Precisión, opcional) Punto 3 avanzado: detección de oclusión con Face Mesh y norma del embedding como calidad.
- [ ] Decidir si se mantiene la galería precargada del PC o si en el futuro todo se registra desde el celular (aún se conserva).
- Ideas futuras (no implementadas): selector de fecha en Historial, contador "X de Y registrados", foto por registro, autenticación en la sincronización.
