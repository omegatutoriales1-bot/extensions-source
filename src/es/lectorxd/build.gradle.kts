import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "LectorXD"
    versionCode = 1
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        name = "LectorXD"
        baseUrl = "https://lectorxd.com"
        lang = "es"
    }

    deeplink {
        path("/manga/..*")
        path("/manhwa/..*")
        path("/manhua/..*")
        path("/novela/..*")
    }
}
