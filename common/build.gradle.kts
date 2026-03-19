plugins {
    id("java-library")
    kotlin("jvm") version "1.9.22"
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    jvmToolchain(11)
}

dependencies {
    implementation("org.lz4:lz4-java:1.8.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.77")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.22")
}
