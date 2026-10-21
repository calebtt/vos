package com.vos.actuator;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** After boot, checks the assistant role (decision 45) even if nothing else starts the actuator. */
public final class BootCheck extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            ((ActuatorApp) context.getApplicationContext()).roleWatch().check("boot");
        }
    }
}
