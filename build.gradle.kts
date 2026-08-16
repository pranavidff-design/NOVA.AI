plugins {
    id("com.android.application") version "8.5.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("com.google.devtools.ksp") version "1.9.24-1.0.20" apply false
    // Reads app/google-services.json (downloaded from the Firebase console) and wires
    // up Firebase at build time — this is how Nova's cloud brain gets configured
    // WITHOUT any API key ever being typed into source code. See README "Cloud brain setup".
    id("com.google.gms.google-services") version "4.4.2" apply false
}
