package com.englishtalk.app

import android.app.Application
import com.englishtalk.app.ads.AdManager
import com.englishtalk.app.billing.BillingManager

class EnglishTalkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Initialize AdMob and In-App Billing
        AdManager.initialize(this)
        BillingManager.initialize(this)
    }
}
