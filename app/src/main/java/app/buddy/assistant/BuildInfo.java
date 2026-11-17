package app.buddy.assistant;

/** Single source of the app version; read by build.sh and app/build.gradle.kts. */
final class BuildInfo {
    static final String VERSION = "2.3.11-fork.6";
    static final int VERSION_CODE = 226;

    private BuildInfo() {
    }
}
