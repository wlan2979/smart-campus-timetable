import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun propOrEmpty(key: String, default: String = ""): String {
    return (localProps.getProperty(key) ?: System.getenv(key) ?: default)
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
}

android {
    namespace = "com.campus.timetable"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.campus.timetable"
        minSdk = 26
        targetSdk = 35
        versionCode = 13
        versionName = "2.2.9"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 所有外部接口配置从 local.properties / 环境变量读取，严禁硬编码密钥
        buildConfigField("String", "API_BASE_URL", "\"${propOrEmpty("TIMETABLE_API_BASE_URL", "http://10.0.2.2:8080/")}\"")
        buildConfigField("String", "QWEATHER_API_HOST", "\"${propOrEmpty("QWEATHER_API_HOST", "")}\"")
        buildConfigField("String", "QWEATHER_API_KEY", "\"${propOrEmpty("QWEATHER_API_KEY", "")}\"")
        buildConfigField(
            "String",
            "HOLIDAY_REMOTE_URL_TEMPLATE",
            "\"${propOrEmpty("HOLIDAY_REMOTE_URL_TEMPLATE", "https://raw.githubusercontent.com/NateScarlet/holiday-cn/master/{year}.json")}\""
        )
        buildConfigField(
            "String",
            "SCHOOL_CATALOG_REMOTE_URL",
            "\"${propOrEmpty("SCHOOL_CATALOG_REMOTE_URL", "")}\""
        )
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
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
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*"
            )
        }
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":domain"))
    implementation(project(":data"))
    implementation(project(":school-adapters"))
    implementation(project(":feature-timetable"))
    implementation(project(":feature-import"))
    implementation(project(":feature-alarm"))
    implementation(project(":feature-weather"))
    implementation(project(":feature-holiday"))
    implementation(project(":feature-sync"))
    implementation(project(":feature-settings"))
    implementation(project(":feature-widgets"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.pdfbox.android)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core.ktx)
}
