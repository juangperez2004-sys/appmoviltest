plugins {
    id("com.android.application")
}

android {
    namespace = "com.juan.asistenciaapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.juan.asistenciaapp"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Solo librerías nativas arm64-v8a: todos los Android 12+ son de 64 bits,
        // así el APK pasa de ~147 MB a ~55 MB sin perder funcionalidad.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")

    // CameraX
    val cameraxVersion = "1.4.2"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // MediaPipe: detección de rostro (BlazeFace)
    // 0.10.9: versión estable y muy usada. 0.10.14 crashea (SIGSEGV nativo) al
    // cargar FaceDetector en varios celulares Android 12/13; si en el teléfono
    // de la compañera sigue cerrando, el archivo diag.txt dirá si es esta lib.
    implementation("com.google.mediapipe:tasks-vision:0.10.9")

    // ONNX Runtime: modelo w600k_mbf (embeddings 512-d)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.23.0")

    // Sincronización WiFi local: servidor HTTP embebido + QR (ZXing)
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.zxing:core:3.5.3")
}
