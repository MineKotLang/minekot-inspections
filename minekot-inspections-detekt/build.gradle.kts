plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {

    implementation(project(":minekot-inspections-core"))
    compileOnly(libs.detekt.api)
    compileOnly(libs.kotlin.compiler)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.detekt.api)
    testImplementation(libs.kotlin.compiler)
}
