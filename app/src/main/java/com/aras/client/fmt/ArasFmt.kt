package com.aras.client.fmt

import com.aras.client.AppConfig
import com.aras.client.dto.ArasShareItem
import com.aras.client.dto.entities.ProfileItem
import com.aras.client.util.JsonUtil
import com.aras.client.util.LogUtil
import com.aras.client.util.Utils

object ArasFmt : FmtBase() {
    fun parse(str: String): ProfileItem? {
        try {
            val jsonBase64Payload = str.substringAfterLast('/')
            val jsonPayload = Utils.decode(jsonBase64Payload)
            val xrayNShareItem = JsonUtil.fromJson(jsonPayload, ArasShareItem::class.java)
            return xrayNShareItem?.toProfileItem()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to parse XrayN format", e)
        }
        return null
    }
}