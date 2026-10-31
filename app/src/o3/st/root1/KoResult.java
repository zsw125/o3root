package o3.st.root1;

/** Outcome of {@link CustomKo#validate(byte[], String)}. */
public final class KoResult {

    public final int level;
    public final String reason;
    public final String name;
    public final String vermagic;
    public final String kernelRelease;

    KoResult(int level, String reason, String name, String vermagic, String kernelRelease) {
        this.level = level;
        this.reason = reason == null ? "" : reason;
        this.name = name == null ? "" : name;
        this.vermagic = vermagic == null ? "" : vermagic;
        this.kernelRelease = kernelRelease == null ? "" : kernelRelease;
    }

    public boolean failed() {
        return level == CustomKo.FAIL;
    }
}
