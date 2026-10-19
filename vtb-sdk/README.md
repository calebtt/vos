# vtb-sdk — `com.vos.vtb`

Android library for third-party VOS Tool Bus providers (TARGET-PLAN §3.4). M0 holds only `VtbContract`: the provider action `com.vos.vtb.PROVIDER`, the `com.vos.vtb.tools` meta-data key, the `res/raw/vtb_tools.json` resource name, and the `com.vos.permission.BIND_VTB_PROVIDER` permission name. The AIDL surface arrives in M5.

Test: `./gradlew :vtb-sdk:testDebugUnitTest` (`VtbContractTest`).
