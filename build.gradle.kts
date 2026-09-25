import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import net.fabricmc.loom.api.LoomGradleExtensionAPI

plugins {
	id("net.fabricmc.fabric-loom") apply false
	id("net.fabricmc.fabric-loom-remap") apply false
	`maven-publish`
	id("org.jetbrains.kotlin.jvm") version "2.4.10"
}

fun prop(name: String): String = project.findProperty(name)?.toString() ?: error("Missing property $name")

val mcVersion = prop("minecraft_version")
val isUnobfuscated = mcVersion.startsWith("26.")
version = "${prop("version")}-$mcVersion"

if (isUnobfuscated) {
	apply(plugin = "net.fabricmc.fabric-loom")
} else {
	apply(plugin = "net.fabricmc.fabric-loom-remap")
}

val loom = project.extensions.getByName("loom") as LoomGradleExtensionAPI

repositories {
	maven {
		url = uri("https://repo.plasmoverse.com/releases")
	}
	maven {
		url = uri("https://repo.plo.su")
	}
	maven {
		url = uri("https://api.modrinth.com/maven")
	}
}

fun DependencyHandlerScope.modImpl(dep: Any) {
	if (isUnobfuscated) {
		implementation(dep)
	} else {
		"modImplementation"(dep)
	}
}

fun DependencyHandlerScope.modCompOnly(dep: Any) {
	if (isUnobfuscated) {
		compileOnly(dep)
	} else {
		"modCompileOnly"(dep)
	}
}

dependencies {
	// To change the versions see the gradle.properties file
	"minecraft"("com.mojang:minecraft:$mcVersion")
	if (!isUnobfuscated) {
		"mappings"(loom.officialMojangMappings())
	}
	modImpl("net.fabricmc:fabric-loader:${prop("loader_version")}")

	// Fabric API. This is technically optional, but you probably want it anyway.
	modImpl("net.fabricmc.fabric-api:fabric-api:${prop("fabric_api_version")}")
	modImpl("net.fabricmc:fabric-language-kotlin:${prop("fabric_kotlin_version")}")

	modCompOnly("maven.modrinth:simple-voice-chat:${prop("svc_version")}")

	implementation("su.plo.voice:protocol:2.1.10")
	"include"("su.plo.voice:protocol:2.1.10")

	implementation("su.plo.slib:api-common:1.5.4")
	"include"("su.plo.slib:api-common:1.5.4")
}

tasks.processResources {
	val version = version
	inputs.property("version", version)

	filesMatching("fabric.mod.json") {
		expand("version" to version)
	}
}

tasks.withType<JavaCompile>().configureEach {
	options.release = if (isUnobfuscated) 25 else 21
}

kotlin {
	compilerOptions {
		jvmTarget = if (isUnobfuscated) JvmTarget.JVM_25 else JvmTarget.JVM_21
	}
}

java {
	// Loom will automatically attach sourcesJar to a RemapSourcesJar task and to the "build" task
	// if it is present.
	// If you remove this line, sources will not be generated.
	withSourcesJar()

	sourceCompatibility = if (isUnobfuscated) JavaVersion.VERSION_25 else JavaVersion.VERSION_21
	targetCompatibility = if (isUnobfuscated) JavaVersion.VERSION_25 else JavaVersion.VERSION_21
}

base {
	archivesName.set("voice-plus-pv2svc")
}

tasks.jar {
	val projectName = project.name
	inputs.property("projectName", projectName)

	from("LICENSE") {
		rename { "${it}_$projectName" }
	}
}

// configure the maven publication
publishing {
	publications {
		register<MavenPublication>("mavenJava") {
			from(components["java"])
		}
	}

	// See https://docs.gradle.org/current/userguide/publishing_maven.html for information on how to set up publishing.
	repositories {
		// Add repositories to publish to here.
		// Notice: This block does NOT have the same function as the block in the top level.
		// The repositories here will be used for publishing your artifact, not for
		// retrieving dependencies.
	}
}
