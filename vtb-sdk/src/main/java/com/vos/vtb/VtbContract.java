package com.vos.vtb;

/** Names a VTB provider app declares (§3.4 Discovery). The AIDL surface arrives with M5. */
public final class VtbContract {
    /** Intent action a provider service declares. */
    public static final String ACTION_PROVIDER = "com.vos.vtb.PROVIDER";
    /** Service meta-data key pointing at the tool manifest. */
    public static final String META_DATA_TOOLS = "com.vos.vtb.tools";
    /** Raw resource name of the tool manifest ({@code res/raw/vtb_tools.json}). */
    public static final String TOOLS_RAW_RESOURCE = "vtb_tools";
    /** Defined by the actuator, {@code signature|privileged}: only the OS agent binds providers. */
    public static final String PERMISSION_BIND_PROVIDER = "com.vos.permission.BIND_VTB_PROVIDER";

    private VtbContract() {
    }
}
