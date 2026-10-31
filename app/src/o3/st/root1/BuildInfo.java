package o3.st.root1;

/**
 * Build variant constants. This tracked copy is the stable release shape
 * (console compiled out). The beta build generates an override under
 * build/gen and excludes this file from javac; see app/build.sh.
 */
final class BuildInfo {
    static final boolean CONSOLE = false;
    static final String VERSION = "3.2";
}
