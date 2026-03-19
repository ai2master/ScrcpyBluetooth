plugins {
    id("java-library")
    kotlin("jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    jvmToolchain(11)
}

dependencies {
    implementation(project(":common"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.22")
    compileOnly(files("${System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: "/opt/android-sdk"}/platforms/android-34/android.jar"))
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.scrcpybt.server.ServerMain"
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
