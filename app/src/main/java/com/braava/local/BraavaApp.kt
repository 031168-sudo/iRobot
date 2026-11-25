package com.braava.local

import android.app.Application
import com.braava.local.data.RobotRepository
import com.braava.local.data.XiaomiRepository

class BraavaApp : Application() {

    lateinit var repository: RobotRepository
        private set

    lateinit var xiaomi: XiaomiRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = RobotRepository(this)
        xiaomi = XiaomiRepository(this)
    }
}
