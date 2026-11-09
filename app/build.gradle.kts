plugins {
    // AGP 9.x имеет встроенную поддержку Kotlin — отдельный kotlin-android плагин не нужен
    id("com.android.application")
}

android {
    namespace = "dev.belsky.vowifiwatch"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.belsky.vowifiwatch"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        // У jar'ов BouncyCastle (тянутся из adb-kt) одинаковые META-INF файлы
        resources {
            merges += "META-INF/LICENSE.md"
            merges += "META-INF/LICENSE"
            merges += "META-INF/NOTICE.md"
            merges += "META-INF/NOTICE"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// adb-kt и его зависимости собраны Kotlin 2.4, а встроенный в AGP 9.4 компилятор —
// Kotlin 2.2.10: разрешаем чтение более новых метаданных, иначе ошибка
// "compiled with an incompatible version of Kotlin (metadata 2.4.0)".
tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class.java).configureEach {
    compilerOptions.freeCompilerArgs.add("-Xskip-metadata-version-check")
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // Обход ограничения на скрытые API (нужен для ImsMmTelManager)
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
    // Выполнение root-команд (репозиторий JitPack, см. settings.gradle.kts)
    implementation("com.github.topjohnwu.libsu:core:5.2.2")
    // Локальный ADB (беспроводная отладка, uid 2000) — репозиторий JitPack.
    // На Maven Central артефакт io.github.rhythmcache:adb-kt:1.0.0 не опубликован.
    implementation("com.github.rhythmcache:adb-kt:v1.0.6")

    testImplementation("junit:junit:4.13.2")
}
