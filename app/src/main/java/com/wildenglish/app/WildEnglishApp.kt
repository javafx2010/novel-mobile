package com.wildenglish.app

import android.app.Application
import android.content.Context
import com.wildenglish.app.i18n.LocaleHelper
import com.wildenglish.app.i18n.Strings

/** Applies the in-app language to all components (activities, services, notifications). */
class WildEnglishApp : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        Strings.init(this)
    }
}
