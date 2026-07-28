plugins {
	java
	id("org.springframework.boot") version "3.4.1"
	id("io.spring.dependency-management") version "1.1.7"
}

fun getGitHash(): String {
	return providers.exec {
		commandLine("git", "rev-parse", "--short", "HEAD")
	}.standardOutput.asText.get().trim()
}

group = "kr.hhplus.be"
version = getGitHash()

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(17)
	}
}

repositories {
	mavenCentral()
}

dependencyManagement {
	imports {
		// ✅ Boot BOM을 명시적으로 넣어서 스타터 버전 해석을 확실히 고정
		mavenBom("org.springframework.boot:spring-boot-dependencies:3.4.1")

		mavenBom("org.springframework.cloud:spring-cloud-dependencies:2024.0.0")
		mavenBom("org.testcontainers:testcontainers-bom:1.20.4")
	}
}

dependencies {
	// Spring
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.springframework.boot:spring-boot-starter-logging")

	// redis
	implementation("org.springframework.boot:spring-boot-starter-data-redis")

	// kafka (✅ 버전 제거)
	implementation("org.springframework.kafka:spring-kafka")

	// DB
	runtimeOnly("com.mysql:mysql-connector-j")

	// swagger
	// Boot 3.4(Spring Framework 6.2)와 호환되는 버전.
	// 2.6.0은 Spring 6.1 기준이라 /v3/api-docs 요청 시
	// NoSuchMethodError: ControllerAdviceBean.<init>(Object) 로 500이 발생한다.
	implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.7.0")

	// Test
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.testcontainers:junit-jupiter")
	testImplementation("org.testcontainers:mysql")

	testImplementation("org.springframework.kafka:spring-kafka-test")
	testImplementation("org.testcontainers:kafka")

	testImplementation("org.awaitility:awaitility:4.2.1")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}


tasks.withType<Test> {
	useJUnitPlatform()
	systemProperty("user.timezone", "UTC")
}
