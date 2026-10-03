plugins {
    alias(kei.plugins.multisrc)
}

dependencies {
    api(project(":lib:secretstream"))
    api(project(":lib:i18n"))
    api("com.dylibso.chicory:runtime:1.4.0")
}

keiyoushi {
    baseVersionCode = 2
    libVersion = "1.6"

    deeplink {
        path("/serie/..*")
    }
}
