pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        if (providers.gradleProperty("includeBooxSdk").orNull == "true") {
            maven {
                url = uri("http://repo.boox.com/repository/maven-public/")
                isAllowInsecureProtocol = true
            }
        }
    }
}

rootProject.name = "Palma Screen Scrub"
include(":app")

