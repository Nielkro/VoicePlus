pluginManagement {
	repositories {
		maven {
			name = "Fabric"
			url = uri("https://maven.fabricmc.net/")
		}
		mavenCentral()
		gradlePluginPortal()
		maven {
			name = "Kikugie Releases"
			url = uri("https://maven.kikugie.dev/releases")
		}
	}

	plugins {
		id("dev.kikugie.stonecutter") version "0.6.1"
		id("net.fabricmc.fabric-loom") version providers.gradleProperty("loom_version")
		id("net.fabricmc.fabric-loom-remap") version providers.gradleProperty("loom_version")
	}
}

plugins {
	id("dev.kikugie.stonecutter")
}

stonecutter {
	create(rootProject) {
		versions("1.21.4", "1.21.11", "26.1", "26.2", "26.3")
	}
}

// Should match your modid
rootProject.name = "voice-plus-pv2svc"
