plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

group = "ai.mimo.plugin"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    intellijPlatform {
        local("D:/Program Files/JetBrains/PyCharm 2025.3.1.1")
        instrumentationTools()
        pluginVerifier()
        zipSigner()
    }
}

tasks {
    patchPluginXml {
        sinceBuild.set("253")
        untilBuild.set("253.*")
    }

    buildSearchableOptions {
        enabled = false
    }

    signPlugin {
        certificateChainFile.set(file("certificate-chain.pem"))
        privateKeyFile.set(file("private.pem"))
    }
}
