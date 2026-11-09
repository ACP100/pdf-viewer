package com.pdfviewer.app

import android.app.Application
import com.pdfviewer.app.data.Library
import com.pdfviewer.app.data.Settings
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Library.init(this)
        Settings.init(this)
        PDFBoxResourceLoader.init(this)
    }
}
