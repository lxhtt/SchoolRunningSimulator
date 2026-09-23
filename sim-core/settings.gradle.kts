pluginManagement {
    repositories {
        if (providers.environmentVariable("RATEMOCK_USE_MIRRORS").orNull == "true") {
            maven("https://maven.aliyun.com/repository/gradle-plugin")
            maven("https://maven.aliyun.com/repository/public")
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (providers.environmentVariable("RATEMOCK_USE_MIRRORS").orNull == "true") {
            maven("https://maven.aliyun.com/repository/public")
        }
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "sim-core"
