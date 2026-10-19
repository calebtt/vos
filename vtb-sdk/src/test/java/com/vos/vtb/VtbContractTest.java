package com.vos.vtb;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** The contract names are public API for third-party providers; renaming one breaks them. */
public class VtbContractTest {
    @Test
    public void namesMatchThePlan() {
        assertEquals("com.vos.vtb.PROVIDER", VtbContract.ACTION_PROVIDER);
        assertEquals("com.vos.vtb.tools", VtbContract.META_DATA_TOOLS);
        assertEquals("vtb_tools", VtbContract.TOOLS_RAW_RESOURCE);
        assertEquals("com.vos.permission.BIND_VTB_PROVIDER", VtbContract.PERMISSION_BIND_PROVIDER);
    }
}
