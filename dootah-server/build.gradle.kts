plugins { application }
group = "dev.dootah"
version = rootProject.extra["dootahVersion"] as String
java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }
application { mainClass.set("dev.dootah.server.DootahServer") }
dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.5.16"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation(project(":dootah-contract"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.test { useJUnitPlatform() }
