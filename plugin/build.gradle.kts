plugins {
	java
}

group = "com.livecraft"
version = "0.1.0"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
	maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
	// Paper 서버가 실행 시 제공 (Gson, Adventure 포함)
	compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
}

tasks.withType<JavaCompile> {
	options.encoding = "UTF-8" // 윈도우 기본(MS949)으로 한글이 깨지지 않게
}

tasks.jar {
	archiveFileName = "BlockcastPlugin.jar"
}

tasks.processResources {
	filesMatching("plugin.yml") {
		expand("version" to project.version)
	}
}
