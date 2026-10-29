package com.example.dooropener

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 開機或 App 被覆蓋安裝後重新註冊地理圍欄；不會啟動前景定位服務。 */
class GeofenceBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val pending = goAsync()
            GeofenceManager.reRegisterAfterBoot(context) {
                pending.finish()
            }
        }
    }
}
